#include <stdlib.h>
#include <string.h>

#include "esp_ota_ops.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"

#include "fidont.h"

#define PACKET 64
#define MAX_INFO 512
#define EVENTS 16
#define KEEPALIVE_INTERVAL pdMS_TO_TICKS(100)

#define KEEPALIVE_PROCESSING 1
#define KEEPALIVE_USER_NEEDED 2
#define CTAP2_GET_INFO 0x04
#define CTAP2_ERR_KEEPALIVE_CANCEL 0x2d

enum source { SOURCE_USB, SOURCE_LINK, SOURCE_DISCONNECT };

struct event {
    enum source source;
    uint8_t type;
    size_t size;
    uint8_t *data;
};

static QueueHandle_t events;

static struct {
    bool active;
    bool forwarded;
    uint32_t channel;
    size_t size;
    uint8_t data[MAX_MESSAGE];
} request;

static uint8_t info[MAX_INFO];
static size_t info_size;

static const esp_partition_t *update_partition;
static esp_ota_handle_t update;

static void post(enum source source, uint8_t type, const uint8_t *data, size_t size)
{
    struct event event = {source, type, size, malloc(size)};
    if (size && !event.data) {
        return;
    }
    memcpy(event.data, data, size);
    xQueueSend(events, &event, portMAX_DELAY);
}

void on_packet(const uint8_t *packet) { post(SOURCE_USB, 0, packet, PACKET); }

void on_message(uint8_t type, const uint8_t *data, size_t size) { post(SOURCE_LINK, type, data, size); }

void on_disconnect(void) { post(SOURCE_DISCONNECT, 0, NULL, 0); }

void on_request(uint32_t channel, const uint8_t *data, size_t size)
{
    if (request.active) {
        uint8_t error = ERROR_BUSY;
        usb_send(channel, CTAPHID_ERROR, &error, 1);
        return;
    }
    // the host asks for this before the phone is near
    if (data[0] == CTAP2_GET_INFO && info_size) {
        uint8_t reply[1 + MAX_INFO] = {0};
        memcpy(reply + 1, info, info_size);
        usb_send(channel, CTAPHID_CBOR, reply, 1 + info_size);
        return;
    }
    request.active = true;
    request.channel = channel;
    request.size = size;
    memcpy(request.data, data, size);
    request.forwarded = link_send(LINK_REQUEST, request.data, request.size);
}

void on_cancel(uint32_t channel)
{
    if (!request.active || channel != request.channel) {
        return;
    }
    if (request.forwarded) {
        link_send(LINK_CANCEL, NULL, 0);
    }
    request.active = false;
    uint8_t status = CTAP2_ERR_KEEPALIVE_CANCEL;
    usb_send(channel, CTAPHID_CBOR, &status, 1);
}

static void handle(uint8_t type, const uint8_t *data, size_t size)
{
    switch (type) {
    case LINK_INFO:
        if (size <= MAX_INFO && (size != info_size || memcmp(info, data, size) != 0)) {
            memcpy(info, data, size);
            info_size = size;
            store_set_info(info, size);
        }
        if (request.active) {
            request.forwarded = link_send(LINK_REQUEST, request.data, request.size);
        }
        break;
    case LINK_RESPONSE:
        if (request.active) {
            request.active = false;
            usb_send(request.channel, CTAPHID_CBOR, data, size);
        }
        break;
    case LINK_UPDATE_BEGIN:
        if (update_partition) {
            esp_ota_abort(update);
        }
        update_partition = esp_ota_get_next_update_partition(NULL);
        if (esp_ota_begin(update_partition, OTA_WITH_SEQUENTIAL_WRITES, &update) != ESP_OK) {
            update_partition = NULL;
        }
        break;
    case LINK_UPDATE_DATA:
        if (update_partition && esp_ota_write(update, data, size) != ESP_OK) {
            esp_ota_abort(update);
            update_partition = NULL;
        }
        break;
    case LINK_UPDATE_END:
        if (update_partition && esp_ota_end(update) == ESP_OK &&
            esp_ota_set_boot_partition(update_partition) == ESP_OK) {
            esp_restart();
        }
        update_partition = NULL;
        break;
    case LINK_LIGHT:
        if (size == LIGHT_SIZE) {
            light_set(data);
        }
        break;
    }
}

void app_main(void)
{
    events = xQueueCreate(EVENTS, sizeof(struct event));
    info_size = store_info(info, sizeof(info));
    light_start();
    usb_start();
    link_start();

    TickType_t keepalive = 0;
    for (;;) {
        struct event event;
        if (xQueueReceive(events, &event, KEEPALIVE_INTERVAL)) {
            switch (event.source) {
            case SOURCE_USB:
                usb_packet(event.data);
                break;
            case SOURCE_LINK:
                handle(event.type, event.data, event.size);
                break;
            case SOURCE_DISCONNECT:
                request.forwarded = false;
                if (update_partition) {
                    esp_ota_abort(update);
                    update_partition = NULL;
                }
                break;
            }
            free(event.data);
        }

        TickType_t now = xTaskGetTickCount();
        if (request.active && now - keepalive >= KEEPALIVE_INTERVAL) {
            uint8_t status = request.forwarded ? KEEPALIVE_USER_NEEDED : KEEPALIVE_PROCESSING;
            usb_send(request.channel, CTAPHID_KEEPALIVE, &status, 1);
            keepalive = now;
        }
        usb_tick();
        light_tick(request.active);
    }
}
