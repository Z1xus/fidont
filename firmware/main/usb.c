#include <string.h>

#include "class/hid/hid_device.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "tinyusb.h"
#include "tinyusb_default_config.h"

#include "fidont.h"

#define PACKET 64
#define INIT_HEADER 7
#define CONTINUATION_HEADER 5
#define BROADCAST 0xffffffff
#define NONCE_SIZE 8

#define CTAPHID_PING 0x81
#define CTAPHID_INIT 0x86
#define CTAPHID_CANCEL 0x91

#define ERROR_INVALID_COMMAND 0x01
#define ERROR_INVALID_LENGTH 0x03
#define ERROR_INVALID_SEQUENCE 0x04
#define ERROR_TIMEOUT 0x05
#define ERROR_INVALID_CHANNEL 0x0b

#define PROTOCOL_VERSION 2
#define CAPABILITY_CBOR 0x04
#define CAPABILITY_NO_MSG 0x08

// the host must send the rest of a message within this time
#define MESSAGE_TIMEOUT pdMS_TO_TICKS(500)
#define WRITE_TIMEOUT pdMS_TO_TICKS(100)

static const uint8_t report_descriptor[] = {TUD_HID_REPORT_DESC_FIDO_U2F(PACKET)};

static const tusb_desc_device_t device_descriptor = {
    .bLength = sizeof(tusb_desc_device_t),
    .bDescriptorType = TUSB_DESC_DEVICE,
    .bcdUSB = 0x0200,
    .bMaxPacketSize0 = CFG_TUD_ENDPOINT0_SIZE,
    // Espressif's vendor id with the product id of its TinyUSB HID examples
    .idVendor = 0x303a,
    .idProduct = 0x4004,
    .bcdDevice = 0x0100,
    .iManufacturer = 1,
    .iProduct = 2,
    .bNumConfigurations = 1,
};

static const char *strings[] = {(const char[]){0x09, 0x04}, "fidont", "fidont dongle"};

static const uint8_t configuration[] = {
    TUD_CONFIG_DESCRIPTOR(1, 1, 0, TUD_CONFIG_DESC_LEN + TUD_HID_INOUT_DESC_LEN, 0, 100),
    TUD_HID_INOUT_DESCRIPTOR(0, 0, HID_ITF_PROTOCOL_NONE, sizeof(report_descriptor), 0x01, 0x81, PACKET, 5),
};

static struct {
    bool active;
    uint32_t channel;
    uint8_t command;
    uint8_t sequence;
    size_t size;
    size_t received;
    TickType_t deadline;
    uint8_t data[MAX_MESSAGE];
} message;

static uint32_t channels;

static uint32_t read_channel(const uint8_t *packet)
{
    return (uint32_t)packet[0] << 24 | packet[1] << 16 | packet[2] << 8 | packet[3];
}

static void write_channel(uint8_t *packet, uint32_t channel)
{
    packet[0] = channel >> 24;
    packet[1] = channel >> 16;
    packet[2] = channel >> 8;
    packet[3] = channel;
}

// a host that stopped reading must not stall the main task
static void write(const uint8_t *packet)
{
    for (int waited = 0; waited < WRITE_TIMEOUT && !tud_hid_ready(); waited++) {
        vTaskDelay(1);
    }
    tud_hid_report(0, packet, PACKET);
}

void usb_send(uint32_t channel, uint8_t command, const uint8_t *data, size_t size)
{
    uint8_t packet[PACKET] = {0};
    write_channel(packet, channel);
    packet[4] = command;
    packet[5] = size >> 8;
    packet[6] = size;
    size_t sent = size < PACKET - INIT_HEADER ? size : PACKET - INIT_HEADER;
    memcpy(packet + INIT_HEADER, data, sent);
    write(packet);

    for (uint8_t sequence = 0; sent < size; sequence++) {
        size_t chunk = size - sent < PACKET - CONTINUATION_HEADER ? size - sent : PACKET - CONTINUATION_HEADER;
        memset(packet + CONTINUATION_HEADER, 0, PACKET - CONTINUATION_HEADER);
        packet[4] = sequence;
        memcpy(packet + CONTINUATION_HEADER, data + sent, chunk);
        write(packet);
        sent += chunk;
    }
}

static void fail(uint32_t channel, uint8_t error) { usb_send(channel, CTAPHID_ERROR, &error, 1); }

static void init(uint32_t channel, const uint8_t *nonce)
{
    uint8_t reply[NONCE_SIZE + 9];
    memcpy(reply, nonce, NONCE_SIZE);
    write_channel(reply + NONCE_SIZE, channel == BROADCAST ? ++channels : channel);
    reply[NONCE_SIZE + 4] = PROTOCOL_VERSION;
    reply[NONCE_SIZE + 5] = 0;
    reply[NONCE_SIZE + 6] = 0;
    reply[NONCE_SIZE + 7] = 0;
    reply[NONCE_SIZE + 8] = CAPABILITY_CBOR | CAPABILITY_NO_MSG;
    usb_send(channel, CTAPHID_INIT, reply, sizeof(reply));
}

static void dispatch(void)
{
    message.active = false;
    switch (message.command) {
    case CTAPHID_PING:
        usb_send(message.channel, CTAPHID_PING, message.data, message.size);
        break;
    case CTAPHID_CBOR:
        if (message.size == 0) {
            fail(message.channel, ERROR_INVALID_LENGTH);
        } else {
            on_request(message.channel, message.data, message.size);
        }
        break;
    default:
        fail(message.channel, ERROR_INVALID_COMMAND);
    }
}

void usb_packet(const uint8_t *packet)
{
    uint32_t channel = read_channel(packet);
    if (!(packet[4] & 0x80)) {
        if (!message.active || channel != message.channel) {
            return;
        }
        if (packet[4] != message.sequence++) {
            message.active = false;
            fail(channel, ERROR_INVALID_SEQUENCE);
            return;
        }
        size_t chunk = message.size - message.received;
        if (chunk > PACKET - CONTINUATION_HEADER) {
            chunk = PACKET - CONTINUATION_HEADER;
        }
        memcpy(message.data + message.received, packet + CONTINUATION_HEADER, chunk);
        message.received += chunk;
    } else {
        size_t size = packet[5] << 8 | packet[6];
        if (packet[4] == CTAPHID_INIT) {
            if (message.active && channel == message.channel) {
                message.active = false;
            }
            init(channel, packet + INIT_HEADER);
            return;
        }
        if (channel == 0 || channel == BROADCAST) {
            fail(channel, ERROR_INVALID_CHANNEL);
            return;
        }
        if (packet[4] == CTAPHID_CANCEL) {
            on_cancel(channel);
            return;
        }
        if (message.active) {
            fail(channel, ERROR_BUSY);
            return;
        }
        if (size > MAX_MESSAGE) {
            fail(channel, ERROR_INVALID_LENGTH);
            return;
        }
        message.active = true;
        message.channel = channel;
        message.command = packet[4];
        message.sequence = 0;
        message.size = size;
        message.received = size < PACKET - INIT_HEADER ? size : PACKET - INIT_HEADER;
        memcpy(message.data, packet + INIT_HEADER, message.received);
    }
    message.deadline = xTaskGetTickCount() + MESSAGE_TIMEOUT;
    if (message.received == message.size) {
        dispatch();
    }
}

void usb_tick(void)
{
    if (message.active && (int32_t)(xTaskGetTickCount() - message.deadline) > 0) {
        message.active = false;
        fail(message.channel, ERROR_TIMEOUT);
    }
}

uint8_t const *tud_hid_descriptor_report_cb(uint8_t instance) { return report_descriptor; }

uint16_t tud_hid_get_report_cb(uint8_t instance, uint8_t id, hid_report_type_t type, uint8_t *buffer, uint16_t size)
{
    return 0;
}

void tud_hid_set_report_cb(uint8_t instance, uint8_t id, hid_report_type_t type, uint8_t const *buffer, uint16_t size)
{
    if (size == PACKET) {
        on_packet(buffer);
    }
}

void usb_start(void)
{
    tinyusb_config_t config = TINYUSB_DEFAULT_CONFIG();
    config.descriptor.device = &device_descriptor;
    config.descriptor.full_speed_config = configuration;
    config.descriptor.string = strings;
    config.descriptor.string_count = sizeof(strings) / sizeof(strings[0]);
    ESP_ERROR_CHECK(tinyusb_driver_install(&config));
}
