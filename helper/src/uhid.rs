use std::fs::{File, OpenOptions};
use std::io::{self, Read, Write};
use std::thread;

use tokio::sync::mpsc;

pub const PACKET: usize = 64;

// see linux/uhid.h
const EVENT_SIZE: usize = 4376;
const OUTPUT: u32 = 6;
const GET_REPORT: u32 = 9;
const GET_REPORT_REPLY: u32 = 10;
const CREATE2: u32 = 11;
const INPUT2: u32 = 12;
const SET_REPORT: u32 = 13;
const SET_REPORT_REPLY: u32 = 14;
const DESCRIPTOR_SIZE_OFFSET: usize = 260;
const DESCRIPTOR_OFFSET: usize = 280;
const OUTPUT_SIZE_OFFSET: usize = 4100;
const BUS_USB: u16 = 3;
const EIO: u16 = 5;

// the ids of the dongle
const VENDOR: u32 = 0x303a;
const PRODUCT: u32 = 0x4004;

// a FIDO HID device with one 64 byte input report and one 64 byte output report
const DESCRIPTOR: [u8; 34] = [
    0x06, 0xd0, 0xf1, 0x09, 0x01, 0xa1, 0x01, 0x09, 0x20, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08,
    0x95, 0x40, 0x81, 0x02, 0x09, 0x21, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0x91,
    0x02, 0xc0,
];

pub struct Uhid(File);

impl Uhid {
    // the key is gone when this is dropped
    pub fn create() -> io::Result<(Self, mpsc::UnboundedReceiver<[u8; PACKET]>)> {
        let mut file = OpenOptions::new()
            .read(true)
            .write(true)
            .open("/dev/uhid")?;
        let mut event = vec![0; EVENT_SIZE];
        event[..4].copy_from_slice(&CREATE2.to_le_bytes());
        event[4..10].copy_from_slice(b"fidont");
        event[DESCRIPTOR_SIZE_OFFSET..][..2]
            .copy_from_slice(&(DESCRIPTOR.len() as u16).to_le_bytes());
        event[DESCRIPTOR_SIZE_OFFSET + 2..][..2].copy_from_slice(&BUS_USB.to_le_bytes());
        event[DESCRIPTOR_SIZE_OFFSET + 4..][..4].copy_from_slice(&VENDOR.to_le_bytes());
        event[DESCRIPTOR_SIZE_OFFSET + 8..][..4].copy_from_slice(&PRODUCT.to_le_bytes());
        event[DESCRIPTOR_OFFSET..][..DESCRIPTOR.len()].copy_from_slice(&DESCRIPTOR);
        file.write_all(&event)?;

        let (packets, receiver) = mpsc::unbounded_channel();
        let mut reader = file.try_clone()?;
        thread::spawn(move || {
            let mut event = [0; EVENT_SIZE];
            while reader.read(&mut event).is_ok() {
                let id = &event[4..8];
                match u32::from_le_bytes(event[..4].try_into().unwrap()) {
                    OUTPUT => {
                        let size = u16::from_le_bytes(
                            event[OUTPUT_SIZE_OFFSET..][..2].try_into().unwrap(),
                        ) as usize;
                        // the kernel puts the report id in front of the packet, also when it is zero
                        if size >= PACKET
                            && packets
                                .send(event[4 + size - PACKET..][..PACKET].try_into().unwrap())
                                .is_err()
                        {
                            break;
                        }
                    }
                    GET_REPORT => reply(&mut reader, GET_REPORT_REPLY, id),
                    SET_REPORT => reply(&mut reader, SET_REPORT_REPLY, id),
                    _ => {}
                }
            }
        });
        Ok((Self(file), receiver))
    }

    pub fn send(&mut self, packet: &[u8; PACKET]) {
        let mut event = [0; 6 + PACKET];
        event[..4].copy_from_slice(&INPUT2.to_le_bytes());
        event[4..6].copy_from_slice(&(PACKET as u16).to_le_bytes());
        event[6..].copy_from_slice(packet);
        let _ = self.0.write_all(&event);
    }
}

// the key has no feature reports
fn reply(file: &mut File, kind: u32, id: &[u8]) {
    let mut event = [0; 12];
    event[..4].copy_from_slice(&kind.to_le_bytes());
    event[4..8].copy_from_slice(id);
    event[8..10].copy_from_slice(&EIO.to_le_bytes());
    let _ = file.write_all(&event);
}
