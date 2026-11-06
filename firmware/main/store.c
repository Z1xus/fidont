#include <string.h>

#include "esp_partition.h"
#include "nvs.h"
#include "nvs_flash.h"

#include "fidont.h"

#define LINK_SUBTYPE 0x40
#define NAMESPACE "fidont"

static const char magic[4] = {'f', 'd', 'n', 't'};

static nvs_handle_t storage(void)
{
    static nvs_handle_t handle;
    if (!handle) {
        if (nvs_flash_init() != ESP_OK) {
            ESP_ERROR_CHECK(nvs_flash_erase());
            ESP_ERROR_CHECK(nvs_flash_init());
        }
        ESP_ERROR_CHECK(nvs_open(NAMESPACE, NVS_READWRITE, &handle));
    }
    return handle;
}

// the phone writes the secret to the link partition over USB, pairing over Bluetooth keeps it in NVS
bool store_secret(uint8_t secret[SECRET_SIZE])
{
    uint8_t link[sizeof(magic) + SECRET_SIZE];
    const esp_partition_t *partition = esp_partition_find_first(ESP_PARTITION_TYPE_DATA, LINK_SUBTYPE, NULL);
    if (partition && esp_partition_read(partition, 0, link, sizeof(link)) == ESP_OK &&
        memcmp(link, magic, sizeof(magic)) == 0) {
        memcpy(secret, link + sizeof(magic), SECRET_SIZE);
        return true;
    }
    size_t size = SECRET_SIZE;
    return nvs_get_blob(storage(), "secret", secret, &size) == ESP_OK && size == SECRET_SIZE;
}

bool store_set_secret(const uint8_t secret[SECRET_SIZE])
{
    nvs_handle_t handle = storage();
    if (nvs_set_blob(handle, "secret", secret, SECRET_SIZE) != ESP_OK)
        return false;
    esp_err_t result = nvs_erase_key(handle, "unpair");
    return (result == ESP_OK || result == ESP_ERR_NVS_NOT_FOUND) && nvs_commit(handle) == ESP_OK;
}

int store_unpair_state(uint8_t secret[SECRET_SIZE])
{
    uint8_t record[SECRET_SIZE + 1];
    size_t size = sizeof(record);
    esp_err_t result = nvs_get_blob(storage(), "unpair", record, &size);
    // a record from other firmware counts as none, so the dongle cannot boot loop on it
    if (result != ESP_OK || size != sizeof(record) || record[0] > 1)
        return -1;
    memcpy(secret, record + 1, SECRET_SIZE);
    return record[0];
}

bool store_unpair(const uint8_t secret[SECRET_SIZE])
{
    nvs_handle_t handle = storage();
    uint8_t record[SECRET_SIZE + 1] = {0};
    memcpy(record + 1, secret, SECRET_SIZE);
    if (nvs_set_blob(handle, "unpair", record, sizeof(record)) != ESP_OK || nvs_commit(handle) != ESP_OK)
        return false;
    const esp_partition_t *partition = esp_partition_find_first(ESP_PARTITION_TYPE_DATA, LINK_SUBTYPE, NULL);
    if (!partition || esp_partition_erase_range(partition, 0, partition->size) != ESP_OK)
        return false;
    esp_err_t result = nvs_erase_key(handle, "secret");
    return (result == ESP_OK || result == ESP_ERR_NVS_NOT_FOUND) && nvs_commit(handle) == ESP_OK;
}

bool store_unpair_finish(const uint8_t secret[SECRET_SIZE])
{
    uint8_t record[SECRET_SIZE + 1] = {1};
    memcpy(record + 1, secret, SECRET_SIZE);
    nvs_handle_t handle = storage();
    return nvs_set_blob(handle, "unpair", record, sizeof(record)) == ESP_OK && nvs_commit(handle) == ESP_OK;
}

size_t store_info(uint8_t *info, size_t size)
{
    return nvs_get_blob(storage(), "info", info, &size) == ESP_OK ? size : 0;
}

void store_set_info(const uint8_t *info, size_t size)
{
    nvs_set_blob(storage(), "info", info, size);
    nvs_commit(storage());
}

void store_light(uint8_t light[LIGHT_SIZE])
{
    uint8_t stored[LIGHT_SIZE];
    size_t size = LIGHT_SIZE;
    if (nvs_get_blob(storage(), "light", stored, &size) == ESP_OK && size == LIGHT_SIZE) {
        memcpy(light, stored, LIGHT_SIZE);
    }
}

void store_set_light(const uint8_t light[LIGHT_SIZE])
{
    nvs_set_blob(storage(), "light", light, LIGHT_SIZE);
    nvs_commit(storage());
}

void store_usb_id(uint8_t id[USB_ID_SIZE])
{
    uint8_t stored[USB_ID_SIZE];
    size_t size = USB_ID_SIZE;
    if (nvs_get_blob(storage(), "usb_id", stored, &size) == ESP_OK && size == USB_ID_SIZE) {
        memcpy(id, stored, USB_ID_SIZE);
    }
}

void store_set_usb_id(const uint8_t id[USB_ID_SIZE])
{
    nvs_set_blob(storage(), "usb_id", id, USB_ID_SIZE);
    nvs_commit(storage());
}
