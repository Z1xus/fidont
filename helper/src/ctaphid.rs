use std::time::{Duration, Instant};

use crate::uhid::PACKET;

pub const CBOR: u8 = 0x90;
pub const KEEPALIVE: u8 = 0xbb;
pub const ERROR: u8 = 0xbf;
pub const BUSY: u8 = 0x06;

const PING: u8 = 0x81;
const INIT: u8 = 0x86;
const CANCEL: u8 = 0x91;

const INVALID_COMMAND: u8 = 0x01;
const INVALID_LENGTH: u8 = 0x03;
const INVALID_SEQUENCE: u8 = 0x04;
const TIMEOUT: u8 = 0x05;
const INVALID_CHANNEL: u8 = 0x0b;

const INIT_HEADER: usize = 7;
const CONTINUATION_HEADER: usize = 5;
const BROADCAST: u32 = 0xffff_ffff;
const NONCE_SIZE: usize = 8;
const MAX_MESSAGE: usize = 4096;
const PROTOCOL_VERSION: u8 = 2;
const CAPABILITY_CBOR: u8 = 0x04;
const CAPABILITY_NO_MSG: u8 = 0x08;

// the host must send the rest of a message within this time
const MESSAGE_TIMEOUT: Duration = Duration::from_millis(500);

pub type Packets = Vec<[u8; PACKET]>;

pub enum Event {
    Request(u32, Vec<u8>),
    Cancel(u32),
}

struct Message {
    channel: u32,
    command: u8,
    sequence: u8,
    size: usize,
    data: Vec<u8>,
    deadline: Instant,
}

#[derive(Default)]
pub struct Ctaphid {
    channels: u32,
    message: Option<Message>,
}

pub fn packets(channel: u32, command: u8, data: &[u8]) -> Packets {
    let mut packet = [0; PACKET];
    packet[..4].copy_from_slice(&channel.to_be_bytes());
    packet[4] = command;
    packet[5..7].copy_from_slice(&(data.len() as u16).to_be_bytes());
    let (first, rest) = data.split_at(data.len().min(PACKET - INIT_HEADER));
    packet[INIT_HEADER..][..first.len()].copy_from_slice(first);
    let mut packets = vec![packet];
    for (sequence, chunk) in rest.chunks(PACKET - CONTINUATION_HEADER).enumerate() {
        let mut packet = [0; PACKET];
        packet[..4].copy_from_slice(&channel.to_be_bytes());
        packet[4] = sequence as u8;
        packet[CONTINUATION_HEADER..][..chunk.len()].copy_from_slice(chunk);
        packets.push(packet);
    }
    packets
}

fn fail(channel: u32, error: u8) -> Packets {
    packets(channel, ERROR, &[error])
}

impl Ctaphid {
    pub fn packet(&mut self, packet: &[u8; PACKET], replies: &mut Packets) -> Option<Event> {
        let channel = u32::from_be_bytes(packet[..4].try_into().unwrap());
        if packet[4] & 0x80 == 0 {
            let message = self
                .message
                .as_mut()
                .filter(|message| message.channel == channel)?;
            if packet[4] != message.sequence {
                self.message = None;
                replies.extend(fail(channel, INVALID_SEQUENCE));
                return None;
            }
            message.sequence += 1;
            let chunk = (message.size - message.data.len()).min(PACKET - CONTINUATION_HEADER);
            message
                .data
                .extend_from_slice(&packet[CONTINUATION_HEADER..][..chunk]);
            message.deadline = Instant::now() + MESSAGE_TIMEOUT;
        } else {
            let size = u16::from_be_bytes([packet[5], packet[6]]) as usize;
            if packet[4] == INIT {
                self.message.take_if(|message| message.channel == channel);
                let assigned = if channel == BROADCAST {
                    self.channels += 1;
                    self.channels
                } else {
                    channel
                };
                let mut reply = packet[INIT_HEADER..][..NONCE_SIZE].to_vec();
                reply.extend_from_slice(&assigned.to_be_bytes());
                reply.extend_from_slice(&[
                    PROTOCOL_VERSION,
                    0,
                    0,
                    0,
                    CAPABILITY_CBOR | CAPABILITY_NO_MSG,
                ]);
                replies.extend(packets(channel, INIT, &reply));
                return None;
            }
            if channel == 0 || channel == BROADCAST {
                replies.extend(fail(channel, INVALID_CHANNEL));
                return None;
            }
            if packet[4] == CANCEL {
                return Some(Event::Cancel(channel));
            }
            if self.message.is_some() {
                replies.extend(fail(channel, BUSY));
                return None;
            }
            if size > MAX_MESSAGE {
                replies.extend(fail(channel, INVALID_LENGTH));
                return None;
            }
            self.message = Some(Message {
                channel,
                command: packet[4],
                sequence: 0,
                size,
                data: packet[INIT_HEADER..][..size.min(PACKET - INIT_HEADER)].to_vec(),
                deadline: Instant::now() + MESSAGE_TIMEOUT,
            });
        }
        let message = self
            .message
            .take_if(|message| message.data.len() == message.size)?;
        match message.command {
            PING => replies.extend(packets(message.channel, PING, &message.data)),
            CBOR if message.size > 0 => return Some(Event::Request(message.channel, message.data)),
            CBOR => replies.extend(fail(message.channel, INVALID_LENGTH)),
            _ => replies.extend(fail(message.channel, INVALID_COMMAND)),
        }
        None
    }

    pub fn tick(&mut self, replies: &mut Packets) {
        if let Some(message) = self
            .message
            .take_if(|message| Instant::now() > message.deadline)
        {
            replies.extend(fail(message.channel, TIMEOUT));
        }
    }
}
