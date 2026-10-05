use aes_gcm::Aes256Gcm;
use aes_gcm::aead::{Aead, KeyInit, Nonce};
use hkdf::Hkdf;
use p256::PublicKey;
use p256::ecdh::EphemeralSecret;
use p256::elliptic_curve::Generate;
use sha2::{Digest, Sha256};

pub const SECRET_SIZE: usize = 32;
pub const ID_SIZE: usize = 8;
const KEY_SIZE: usize = 32;

pub type Secret = [u8; SECRET_SIZE];

pub fn random<const N: usize>() -> [u8; N] {
    let mut bytes = [0; N];
    getrandom::fill(&mut bytes).expect("no system random source");
    bytes
}

fn derive<const N: usize>(key: &[u8], salt: &[u8], info: &str) -> [u8; N] {
    let mut output = [0; N];
    Hkdf::<Sha256>::new(Some(salt), key)
        .expand(info.as_bytes(), &mut output)
        .expect("output fits HKDF");
    output
}

// names the computer to its phone in the advert
pub fn id(secret: &Secret) -> [u8; ID_SIZE] {
    Sha256::digest(secret)[..ID_SIZE].try_into().unwrap()
}

// returns our public key and the secret that both sides keep
pub fn pair(phone_key: &[u8]) -> Option<(Vec<u8>, Secret)> {
    let phone = PublicKey::from_sec1_bytes(phone_key).ok()?;
    let key = EphemeralSecret::generate();
    let public = key.public_key().to_sec1_bytes().to_vec();
    let shared = key.diffie_hellman(&phone);
    let salt = [phone_key, &public].concat();
    Some((
        public.clone(),
        derive(shared.raw_secret_bytes(), &salt, "fidont pair"),
    ))
}

// both sides show this after a computer pairs, so a person can compare them
pub fn code(secret: &Secret) -> String {
    let digits = u32::from_be_bytes(derive(secret, &[], "fidont code")) % 1_000_000;
    format!("{:03} {:03}", digits / 1000, digits % 1000)
}

pub fn frame(body: &[u8]) -> Vec<u8> {
    let mut frame = (body.len() as u16).to_le_bytes().to_vec();
    frame.extend_from_slice(body);
    frame
}

pub struct Session {
    rx: Aes256Gcm,
    tx: Aes256Gcm,
    received: u32,
    sent: u32,
}

impl Session {
    pub fn new(secret: &Secret, phone_hello: &[u8], hello: &[u8]) -> Self {
        let keys: [u8; 2 * KEY_SIZE] =
            derive(secret, &[phone_hello, hello].concat(), "fidont link");
        Self {
            rx: Aes256Gcm::new_from_slice(&keys[..KEY_SIZE]).unwrap(),
            tx: Aes256Gcm::new_from_slice(&keys[KEY_SIZE..]).unwrap(),
            received: 0,
            sent: 0,
        }
    }

    pub fn open(&mut self, body: &[u8]) -> Option<Vec<u8>> {
        let nonce = nonce(self.received);
        self.received += 1;
        self.rx
            .decrypt(&nonce, body)
            .ok()
            .filter(|message| !message.is_empty())
    }

    pub fn seal(&mut self, kind: u8, payload: &[u8]) -> Vec<u8> {
        let nonce = nonce(self.sent);
        self.sent += 1;
        let message = [&[kind], payload].concat();
        frame(
            &self
                .tx
                .encrypt(&nonce, message.as_slice())
                .expect("message fits AES-GCM"),
        )
    }
}

fn nonce(count: u32) -> Nonce<Aes256Gcm> {
    let mut nonce = [0; 12];
    nonce[8..].copy_from_slice(&count.to_be_bytes());
    nonce.into()
}
