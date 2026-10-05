#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define SECRET_SIZE 32
#define MAX_MESSAGE 4096

// a mode, then red, green and blue
#define LIGHT_SIZE 4

// link messages from the phone
#define LINK_INFO 1
#define LINK_RESPONSE 2
#define LINK_UPDATE_BEGIN 3
#define LINK_UPDATE_DATA 4
#define LINK_UPDATE_END 5
#define LINK_LIGHT 6

// link messages to the phone
#define LINK_STATUS 1
#define LINK_REQUEST 2
#define LINK_CANCEL 3

#define CTAPHID_CBOR 0x90
#define CTAPHID_KEEPALIVE 0xbb
#define CTAPHID_ERROR 0xbf
#define ERROR_BUSY 0x06

bool store_secret(uint8_t secret[SECRET_SIZE]);
void store_set_secret(const uint8_t secret[SECRET_SIZE]);
size_t store_info(uint8_t *info, size_t size);
void store_set_info(const uint8_t *info, size_t size);
void store_light(uint8_t light[LIGHT_SIZE]);
void store_set_light(const uint8_t light[LIGHT_SIZE]);

void usb_start(void);
void usb_packet(const uint8_t *packet);
void usb_tick(void);
void usb_send(uint32_t channel, uint8_t command, const uint8_t *data, size_t size);

void light_start(void);
void light_set(const uint8_t light[LIGHT_SIZE]);
void light_tick(bool request);

void link_start(void);
bool link_send(uint8_t type, const uint8_t *data, size_t size);
void link_wait(bool wait);

// called from the USB and Bluetooth tasks, main.c queues them for the main task
void on_packet(const uint8_t *packet);
void on_message(uint8_t type, const uint8_t *data, size_t size);
void on_disconnect(void);

// called from the main task
void on_request(uint32_t channel, const uint8_t *data, size_t size);
void on_cancel(uint32_t channel);
