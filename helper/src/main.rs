mod ctaphid;
mod link;
mod uhid;

use std::collections::BTreeMap;
use std::error::Error;
use std::fs;
use std::io::{self, Write};
use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use bluer::adv::{Advertisement, AdvertisementHandle};
use bluer::gatt::CharacteristicWriter;
use bluer::gatt::local::{
    Application, Characteristic, CharacteristicControlEvent, CharacteristicNotify,
    CharacteristicNotifyMethod, CharacteristicWrite, CharacteristicWriteMethod, Service,
    characteristic_control,
};
use bluer::{Adapter, Uuid};
use futures_util::StreamExt;
use tokio::sync::mpsc;
use tokio::time::interval;

use ctaphid::{Ctaphid, Event, Packets, packets};
use link::{Secret, Session};
use uhid::Uhid;

// the last byte of the first group selects the characteristic
const SERVICE: Uuid = Uuid::from_u128(0xf1d0a000_6a0b_4d1e_9f5c_3c1e5d0a7e11);
const RX: Uuid = Uuid::from_u128(0xf1d0a001_6a0b_4d1e_9f5c_3c1e5d0a7e11);
const TX: Uuid = Uuid::from_u128(0xf1d0a002_6a0b_4d1e_9f5c_3c1e5d0a7e11);

// link hellos and messages from the phone
const HELLO: u8 = 1;
const PAIR: u8 = 2;
const INFO: u8 = 1;
const RESPONSE: u8 = 2;

// link messages to the phone
const STATUS: u8 = 1;
const REQUEST: u8 = 2;
const CANCEL: u8 = 3;

const HELLO_SIZE: usize = 16;
const POINT_SIZE: usize = 65;
const FRAME_HEADER: usize = 2;
const ATT_HEADER: usize = 3;

// a dongle sends a build hash of 32 bytes here, so a name must be shorter
const MAX_NAME: usize = 31;

const KEEPALIVE_INTERVAL: Duration = Duration::from_millis(100);
const KEEPALIVE_PROCESSING: u8 = 1;
const KEEPALIVE_USER_NEEDED: u8 = 2;
const GET_INFO: u8 = 0x04;
const KEEPALIVE_CANCEL: u8 = 0x2d;

// a phone must prove that it holds the secret within this time
const AUTH_TIMEOUT: Duration = Duration::from_secs(10);

// the phone listens in short windows while fidont is closed, the default of BlueZ is too slow for them
const ADVERT_INTERVAL: Duration = Duration::from_millis(30);

const UNIT: &str = "fidont-helper.service";

struct Phone {
    writer: CharacteristicWriter,
    session: Option<Session>,
    pairing: Option<Secret>,
    ready: bool,
    incoming: Vec<u8>,
    deadline: Instant,
}

struct Request {
    channel: u32,
    data: Vec<u8>,
    forwarded: bool,
}

struct Helper {
    adapter: Adapter,
    advert: Option<AdvertisementHandle>,
    key: Uhid,
    hid: Ctaphid,
    folder: PathBuf,
    name: String,
    secret: Option<Secret>,
    info: Vec<u8>,
    phone: Option<Phone>,
    request: Option<Request>,
    // the person said that the codes match
    accepted: bool,
}

impl Phone {
    async fn notify(&self, frame: &[u8]) -> bool {
        for chunk in frame.chunks(self.writer.mtu() - ATT_HEADER) {
            if self.writer.send(chunk).await.is_err() {
                return false;
            }
        }
        true
    }

    async fn send(&mut self, kind: u8, payload: &[u8]) -> bool {
        match &mut self.session {
            Some(session) => {
                let frame = session.seal(kind, payload);
                self.notify(&frame).await
            }
            None => false,
        }
    }
}

impl Helper {
    fn write(&mut self, packets: &[[u8; uhid::PACKET]]) {
        for packet in packets {
            self.key.send(packet);
        }
    }

    // a paired computer shows up only while a request waits, so the phone can treat the advert as a call
    async fn advertise(&mut self) -> bluer::Result<()> {
        let wanted = match self.secret {
            Some(_) => self.request.is_some() && self.phone.is_none(),
            None => true,
        };
        if wanted == self.advert.is_some() {
            return Ok(());
        }
        self.advert = None;
        if wanted {
            // the service data names the computer to its phone, a computer with no phone yet sets every bit
            let id = self.secret.as_ref().map_or([0xff; link::ID_SIZE], link::id);
            let advert = Advertisement {
                service_data: BTreeMap::from([(SERVICE, id.to_vec())]),
                discoverable: Some(true),
                min_interval: Some(ADVERT_INTERVAL),
                max_interval: Some(ADVERT_INTERVAL),
                ..Default::default()
            };
            self.advert = Some(self.adapter.advertise(advert).await?);
        }
        Ok(())
    }

    async fn forward(&mut self) {
        if let (Some(request), Some(phone)) = (&mut self.request, &mut self.phone) {
            request.forwarded = phone.ready && phone.send(REQUEST, &request.data).await;
        }
    }

    fn lose(&mut self) {
        self.phone = None;
        if let Some(request) = &mut self.request {
            request.forwarded = false;
        }
    }

    async fn disconnect(&mut self) {
        if let Some(phone) = &self.phone
            && let Ok(device) = self.adapter.device(phone.writer.device_address())
        {
            let _ = device.disconnect().await;
        }
        self.lose();
    }

    async fn hello(&mut self, body: &[u8]) -> bool {
        let Some(phone) = &mut self.phone else {
            return false;
        };
        let reply = match (&self.secret, body) {
            (Some(secret), [HELLO, hello @ ..]) if hello.len() == HELLO_SIZE => {
                let reply = link::random::<HELLO_SIZE>().to_vec();
                phone.session = Some(Session::new(secret, hello, &reply));
                reply
            }
            (None, [PAIR, key @ ..]) if key.len() == POINT_SIZE => {
                let Some((reply, secret)) = link::pair(key) else {
                    return false;
                };
                println!("Code: {}", link::code(&secret));
                print!("Does the phone show this code? [y/N] ");
                let _ = io::stdout().flush();
                phone.session = Some(Session::new(&secret, key, &reply));
                phone.pairing = Some(secret);
                reply
            }
            _ => return false,
        };
        phone.notify(&link::frame(&reply)).await && phone.send(STATUS, self.name.as_bytes()).await
    }

    async fn receive(&mut self, body: &[u8]) -> io::Result<bool> {
        let Some(phone) = &mut self.phone else {
            return Ok(false);
        };
        let Some(session) = &mut phone.session else {
            return Ok(self.hello(body).await);
        };
        let Some(message) = session.open(body) else {
            return Ok(false);
        };
        if !phone.ready && message[0] != INFO {
            return Ok(false);
        }
        phone.ready = true;
        match message[0] {
            INFO => {
                if self.info != message[1..] {
                    self.info = message[1..].to_vec();
                    save(&self.folder.join("info"), &self.info)?;
                }
                self.forward().await;
            }
            RESPONSE => {
                if let Some(request) = self.request.take() {
                    self.write(&packets(request.channel, ctaphid::CBOR, &message[1..]));
                }
            }
            _ => {}
        }
        Ok(true)
    }

    async fn fragment(&mut self, fragment: Vec<u8>) -> io::Result<()> {
        let Some(phone) = &mut self.phone else {
            return Ok(());
        };
        phone.incoming.extend(fragment);
        while let Some(phone) = &mut self.phone
            && phone.incoming.len() >= FRAME_HEADER
        {
            let end =
                FRAME_HEADER + u16::from_le_bytes([phone.incoming[0], phone.incoming[1]]) as usize;
            if phone.incoming.len() < end {
                break;
            }
            let frame: Vec<u8> = phone.incoming.drain(..end).collect();
            if !self.receive(&frame[FRAME_HEADER..]).await? {
                self.disconnect().await;
            }
        }
        Ok(())
    }

    async fn packet(&mut self, packet: [u8; uhid::PACKET]) {
        let mut replies = Packets::new();
        match self.hid.packet(&packet, &mut replies) {
            Some(Event::Request(channel, _)) if self.request.is_some() => {
                replies.extend(packets(channel, ctaphid::ERROR, &[ctaphid::BUSY]));
            }
            // the host asks for this before the phone is near
            Some(Event::Request(channel, data)) if data[0] == GET_INFO && !self.info.is_empty() => {
                replies.extend(packets(
                    channel,
                    ctaphid::CBOR,
                    &[&[0], self.info.as_slice()].concat(),
                ));
            }
            Some(Event::Request(channel, data)) => {
                self.request = Some(Request {
                    channel,
                    data,
                    forwarded: false,
                });
                self.forward().await;
            }
            Some(Event::Cancel(channel)) => {
                if let Some(request) = self.request.take_if(|request| request.channel == channel) {
                    if let (true, Some(phone)) = (request.forwarded, &mut self.phone) {
                        phone.send(CANCEL, &[]).await;
                    }
                    replies.extend(packets(channel, ctaphid::CBOR, &[KEEPALIVE_CANCEL]));
                }
            }
            None => {}
        }
        self.write(&replies);
    }

    async fn tick(&mut self) {
        let mut replies = Packets::new();
        if let Some(request) = &self.request {
            let status = if request.forwarded {
                KEEPALIVE_USER_NEEDED
            } else {
                KEEPALIVE_PROCESSING
            };
            replies.extend(packets(request.channel, ctaphid::KEEPALIVE, &[status]));
        }
        self.hid.tick(&mut replies);
        self.write(&replies);
        // a person needs longer than this to compare the codes
        let late = |phone: &Phone| {
            !phone.ready && phone.pairing.is_none() && Instant::now() > phone.deadline
        };
        if self.phone.as_ref().is_some_and(late) {
            self.disconnect().await;
        }
    }

    // the phone keeps the secret when it sends its info, we keep it when the person agrees too
    fn paired(&mut self) -> io::Result<bool> {
        match &self.phone {
            Some(Phone {
                ready: true,
                pairing: Some(secret),
                ..
            }) if self.accepted => {
                save(&self.folder.join("secret"), secret)?;
                Ok(true)
            }
            _ => Ok(false),
        }
    }
}

fn save(path: &Path, data: &[u8]) -> io::Result<()> {
    fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(path)?
        .write_all(data)
}

fn systemctl(arguments: &[&str]) -> bool {
    Command::new("systemctl")
        .arg("--user")
        .args(arguments)
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|status| status.success())
}

fn install() -> Result<(), Box<dyn Error>> {
    let home = PathBuf::from(std::env::var_os("HOME").ok_or("HOME is not set")?);
    let units = home.join(".config/systemd/user");
    fs::create_dir_all(&units)?;
    let unit = format!(
        "[Unit]\nDescription=fidont helper\n\n[Service]\nExecStart={}\nRestart=on-failure\n\n[Install]\nWantedBy=default.target\n",
        std::env::current_exe()?.display()
    );
    fs::write(units.join(UNIT), unit)?;
    if !systemctl(&["daemon-reload"]) || !systemctl(&["enable", "--now", UNIT]) {
        return Err("systemctl failed".into());
    }
    Ok(())
}

async fn run(pairing: bool) -> Result<(), Box<dyn Error>> {
    let home = std::env::var_os("HOME").ok_or("HOME is not set")?;
    let folder = PathBuf::from(home).join(".local/share/fidont-helper");
    fs::DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(&folder)?;
    let secret: Option<Secret> = fs::read(folder.join("secret"))
        .ok()
        .and_then(|secret| secret.try_into().ok());
    match (pairing, &secret) {
        (true, Some(_)) => return Err("already paired, run `fidont-helper unpair` first".into()),
        (false, None) => {
            println!("not paired, run `fidont-helper pair`");
            return Ok(());
        }
        _ => {}
    }
    let info = fs::read(folder.join("info")).unwrap_or_default();
    let mut name = fs::read_to_string("/proc/sys/kernel/hostname")
        .unwrap_or_default()
        .trim()
        .to_string();
    while name.len() > MAX_NAME {
        name.pop();
    }

    let session = bluer::Session::new().await?;
    let adapter = session.default_adapter().await?;
    adapter.set_powered(true).await?;
    // BlueZ keeps the connection when the helper stops, and the phone then waits on a dead link
    if let Some(address) = fs::read_to_string(folder.join("phone"))
        .ok()
        .and_then(|address| address.parse().ok())
        && let Ok(device) = adapter.device(address)
    {
        let _ = device.disconnect().await;
    }

    let (fragments, mut written) = mpsc::unbounded_channel();
    let (mut control, handle) = characteristic_control();
    let _application = adapter
        .serve_gatt_application(Application {
            services: vec![Service {
                uuid: SERVICE,
                primary: true,
                characteristics: vec![
                    Characteristic {
                        uuid: RX,
                        write: Some(CharacteristicWrite {
                            write: true,
                            method: CharacteristicWriteMethod::Fun(Box::new(move |fragment, _| {
                                let _ = fragments.send(fragment);
                                Box::pin(async { Ok(()) })
                            })),
                            ..Default::default()
                        }),
                        ..Default::default()
                    },
                    Characteristic {
                        uuid: TX,
                        notify: Some(CharacteristicNotify {
                            notify: true,
                            method: CharacteristicNotifyMethod::Io,
                            ..Default::default()
                        }),
                        control_handle: handle,
                        ..Default::default()
                    },
                ],
                ..Default::default()
            }],
            ..Default::default()
        })
        .await?;

    let (answers, mut answer) = mpsc::unbounded_channel();
    if pairing {
        println!("In fidont, tap \"Add a computer\", then \"Pair\".");
        thread::spawn(move || {
            let mut line = String::new();
            while io::stdin().read_line(&mut line).is_ok_and(|size| size > 0) {
                let _ = answers.send(line.trim().eq_ignore_ascii_case("y"));
                line.clear();
            }
        });
    }

    let (key, mut host) = Uhid::create()?;
    let mut helper = Helper {
        adapter,
        advert: None,
        key,
        hid: Ctaphid::default(),
        folder,
        name,
        secret,
        info,
        phone: None,
        request: None,
        accepted: false,
    };

    let mut keepalive = interval(KEEPALIVE_INTERVAL);
    loop {
        helper.advertise().await?;
        tokio::select! {
            Some(packet) = host.recv() => helper.packet(packet).await,
            Some(fragment) = written.recv() => helper.fragment(fragment).await?,
            Some(event) = control.next() => {
                // the phone turns notifications on before it says hello
                if let CharacteristicControlEvent::Notify(writer) = event {
                    helper.disconnect().await;
                    helper.accepted = false;
                    save(&helper.folder.join("phone"), writer.device_address().to_string().as_bytes())?;
                    helper.phone = Some(Phone {
                        writer,
                        session: None,
                        pairing: None,
                        ready: false,
                        incoming: Vec::new(),
                        deadline: Instant::now() + AUTH_TIMEOUT,
                    });
                }
            }
            Some(()) = async { helper.phone.as_ref()?.writer.closed().await.ok() } => helper.lose(),
            Some(accepted) = answer.recv() => {
                if !accepted {
                    return Err("not paired".into());
                }
                helper.accepted = helper.phone.as_ref().is_some_and(|phone| phone.pairing.is_some());
            }
            _ = keepalive.tick() => helper.tick().await,
        }
        if pairing && helper.paired()? {
            println!("Paired.");
            helper.disconnect().await;
            systemctl(&["restart", UNIT]);
            return Ok(());
        }
    }
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<(), Box<dyn Error>> {
    match std::env::args().nth(1).as_deref() {
        None => run(false).await,
        Some("pair") => run(true).await,
        Some("unpair") => {
            let home = PathBuf::from(std::env::var_os("HOME").ok_or("HOME is not set")?);
            let _ = fs::remove_dir_all(home.join(".local/share/fidont-helper"));
            systemctl(&["try-restart", UNIT]);
            Ok(())
        }
        Some("install") => install(),
        Some(_) => Err("usage: fidont-helper [pair | unpair | install]".into()),
    }
}
