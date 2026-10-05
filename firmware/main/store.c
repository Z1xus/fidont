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
            nvs_flash_erase();
            nvs_flash_init();
        }
        nvs_open(NAMESPACE, NVS_READWRITE, &handle);
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

void store_set_secret(const uint8_t secret[SECRET_SIZE])
{
    nvs_set_blob(storage(), "secret", secret, SECRET_SIZE);
    nvs_commit(storage());
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
