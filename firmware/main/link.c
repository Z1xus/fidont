#include <string.h>

#include "esp_app_desc.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "psa/crypto.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

#include "fidont.h"

#define HELLO 1
#define PAIR 2
#define HELLO_SIZE 16
#define POINT_SIZE 65
#define KEY_SIZE 32
#define TAG_SIZE 16
#define NONCE_SIZE 12
#define ID_SIZE 8
#define UUID_SIZE 16
#define FRAME_HEADER 2
#define ATT_HEADER 3
#define MAX_FRAME (FRAME_HEADER + 1 + MAX_MESSAGE + TAG_SIZE)
#define SEND_ATTEMPTS 100

// a phone must prove that it holds the secret within this time
#define AUTH_TIMEOUT_US (10 * 1000 * 1000)

// f1d0a000-6a0b-4d1e-9f5c-3c1e5d0a7e11, the last byte selects the characteristic
#define UUID(index) 0x11, 0x7e, 0x0a, 0x5d, 0x1e, 0x3c, 0x5c, 0x9f, 0x1e, 0x4d, 0x0b, 0x6a, index, 0xa0, 0xd0, 0xf1

static const ble_uuid128_t service_uuid = BLE_UUID128_INIT(UUID(0));
static const ble_uuid128_t rx_uuid = BLE_UUID128_INIT(UUID(1));
static const ble_uuid128_t tx_uuid = BLE_UUID128_INIT(UUID(2));

static uint8_t secret[SECRET_SIZE];
static uint8_t paired_secret[SECRET_SIZE];
static bool paired;
static bool pairing;

static uint8_t address_type;
static uint16_t connection = BLE_HS_CONN_HANDLE_NONE;
static uint16_t tx_handle;
static esp_timer_handle_t auth_timer;
static SemaphoreHandle_t sending;

static struct {
    bool keyed;
    bool ready;
    uint8_t rx_key[KEY_SIZE];
    uint8_t tx_key[KEY_SIZE];
    uint32_t received;
    uint32_t sent;
} session;

static struct {
    size_t size;
    uint8_t data[MAX_FRAME];
} incoming;

static uint8_t outgoing[MAX_FRAME];

static int on_gap(struct ble_gap_event *event, void *arg);

static void derive(const uint8_t *key, const uint8_t *salt, size_t salt_size, const char *info, uint8_t *output,
                   size_t size)
{
    psa_key_derivation_operation_t operation = PSA_KEY_DERIVATION_OPERATION_INIT;
    psa_key_derivation_setup(&operation, PSA_ALG_HKDF(PSA_ALG_SHA_256));
    psa_key_derivation_input_bytes(&operation, PSA_KEY_DERIVATION_INPUT_SALT, salt, salt_size);
    psa_key_derivation_input_bytes(&operation, PSA_KEY_DERIVATION_INPUT_SECRET, key, KEY_SIZE);
    psa_key_derivation_input_bytes(&operation, PSA_KEY_DERIVATION_INPUT_INFO, (const uint8_t *)info, strlen(info));
    psa_key_derivation_output_bytes(&operation, output, size);
    psa_key_derivation_abort(&operation);
}

// the tag follows the data
static bool crypt(bool encrypt, const uint8_t *key, uint32_t count, uint8_t *data, size_t size)
{
    uint8_t nonce[NONCE_SIZE] = {[8] = count >> 24, count >> 16, count >> 8, count};
    psa_key_attributes_t attributes = PSA_KEY_ATTRIBUTES_INIT;
    psa_key_id_t id;
    size_t written;
    psa_set_key_type(&attributes, PSA_KEY_TYPE_AES);
    psa_set_key_algorithm(&attributes, PSA_ALG_GCM);
    psa_set_key_usage_flags(&attributes, encrypt ? PSA_KEY_USAGE_ENCRYPT : PSA_KEY_USAGE_DECRYPT);
    if (psa_import_key(&attributes, key, KEY_SIZE, &id) != PSA_SUCCESS) {
        return false;
    }
    psa_status_t status = encrypt ? psa_aead_encrypt(id, PSA_ALG_GCM, nonce, NONCE_SIZE, NULL, 0, data, size, data,
                                                     size + TAG_SIZE, &written)
                                  : psa_aead_decrypt(id, PSA_ALG_GCM, nonce, NONCE_SIZE, NULL, 0, data, size + TAG_SIZE,
                                                     data, size, &written);
    psa_destroy_key(id);
    return status == PSA_SUCCESS;
}

static bool notify(const uint8_t *data, size_t size)
{
    size_t chunk = ble_att_mtu(connection) - ATT_HEADER;
    for (size_t offset = 0; offset < size; offset += chunk) {
        size_t length = size - offset < chunk ? size - offset : chunk;
        int result = BLE_HS_ENOMEM;
        // the buffers run out when we send faster than the radio
        for (int attempt = 0; attempt < SEND_ATTEMPTS && result == BLE_HS_ENOMEM; attempt++) {
            struct os_mbuf *buffer = ble_hs_mbuf_from_flat(data + offset, length);
            if (buffer) {
                result = ble_gatts_notify_custom(connection, tx_handle, buffer);
            }
            if (result == BLE_HS_ENOMEM) {
                vTaskDelay(pdMS_TO_TICKS(10));
            }
        }
        if (result != 0) {
            return false;
        }
    }
    return true;
}

static bool send(uint8_t type, const uint8_t *data, size_t size)
{
    xSemaphoreTake(sending, portMAX_DELAY);
    size_t body = 1 + size + TAG_SIZE;
    outgoing[0] = body;
    outgoing[1] = body >> 8;
    outgoing[FRAME_HEADER] = type;
    memcpy(outgoing + FRAME_HEADER + 1, data, size);
    bool sent = session.keyed && crypt(true, session.tx_key, session.sent++, outgoing + FRAME_HEADER, 1 + size) &&
                notify(outgoing, FRAME_HEADER + body);
    xSemaphoreGive(sending);
    return sent;
}

bool link_send(uint8_t type, const uint8_t *data, size_t size) { return session.ready && send(type, data, size); }

static bool pair(const uint8_t *phone_key, uint8_t *dongle_key)
{
    psa_key_attributes_t attributes = PSA_KEY_ATTRIBUTES_INIT;
    psa_key_id_t id;
    uint8_t point[KEY_SIZE];
    uint8_t salt[2 * POINT_SIZE];
    size_t written;
    psa_set_key_type(&attributes, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_SECP_R1));
    psa_set_key_bits(&attributes, KEY_SIZE * 8);
    psa_set_key_algorithm(&attributes, PSA_ALG_ECDH);
    psa_set_key_usage_flags(&attributes, PSA_KEY_USAGE_DERIVE);
    if (psa_generate_key(&attributes, &id) != PSA_SUCCESS) {
        return false;
    }
    bool done =
        psa_export_public_key(id, dongle_key, POINT_SIZE, &written) == PSA_SUCCESS &&
        psa_raw_key_agreement(PSA_ALG_ECDH, id, phone_key, POINT_SIZE, point, sizeof(point), &written) == PSA_SUCCESS;
    psa_destroy_key(id);
    if (done) {
        memcpy(salt, phone_key, POINT_SIZE);
        memcpy(salt + POINT_SIZE, dongle_key, POINT_SIZE);
        derive(point, salt, sizeof(salt), "fidont pair", paired_secret, SECRET_SIZE);
    }
    return done;
}

static void hello(const uint8_t *body, size_t size)
{
    uint8_t reply[FRAME_HEADER + POINT_SIZE];
    uint8_t salt[2 * POINT_SIZE];
    size_t reply_size;
    if (paired && size == 1 + HELLO_SIZE && body[0] == HELLO) {
        reply_size = HELLO_SIZE;
        esp_fill_random(reply + FRAME_HEADER, HELLO_SIZE);
    } else if (!paired && size == 1 + POINT_SIZE && body[0] == PAIR && pair(body + 1, reply + FRAME_HEADER)) {
        reply_size = POINT_SIZE;
        pairing = true;
    } else {
        ble_gap_terminate(connection, BLE_ERR_REM_USER_CONN_TERM);
        return;
    }
    reply[0] = reply_size;
    reply[1] = 0;

    uint8_t keys[2 * KEY_SIZE];
    memcpy(salt, body + 1, size - 1);
    memcpy(salt + size - 1, reply + FRAME_HEADER, reply_size);
    derive(pairing ? paired_secret : secret, salt, size - 1 + reply_size, "fidont link", keys, sizeof(keys));
    memcpy(session.rx_key, keys, KEY_SIZE);
    memcpy(session.tx_key, keys + KEY_SIZE, KEY_SIZE);
    session.keyed = true;

    notify(reply, FRAME_HEADER + reply_size);
    send(LINK_STATUS, esp_app_get_description()->app_elf_sha256, KEY_SIZE);
}

static void receive(uint8_t *body, size_t size)
{
    if (!session.keyed) {
        hello(body, size);
        return;
    }
    if (size <= TAG_SIZE || !crypt(false, session.rx_key, session.received++, body, size - TAG_SIZE) ||
        (!session.ready && body[0] != LINK_INFO)) {
        ble_gap_terminate(connection, BLE_ERR_REM_USER_CONN_TERM);
        return;
    }
    if (!session.ready) {
        if (pairing) {
            memcpy(secret, paired_secret, SECRET_SIZE);
            store_set_secret(secret);
            paired = true;
        }
        session.ready = true;
        esp_timer_stop(auth_timer);
    }
    on_message(body[0], body + 1, size - TAG_SIZE - 1);
}

static int on_access(uint16_t handle, uint16_t attribute, struct ble_gatt_access_ctxt *context, void *arg)
{
    if (context->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    size_t size = OS_MBUF_PKTLEN(context->om);
    if (incoming.size + size > sizeof(incoming.data)) {
        incoming.size = 0;
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    os_mbuf_copydata(context->om, 0, size, incoming.data + incoming.size);
    incoming.size += size;
    if (incoming.size >= FRAME_HEADER) {
        size_t body = incoming.data[0] | incoming.data[1] << 8;
        if (incoming.size >= FRAME_HEADER + body) {
            incoming.size = 0;
            receive(incoming.data + FRAME_HEADER, body);
        }
    }
    return 0;
}

static const struct ble_gatt_svc_def services[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &service_uuid.u,
        .characteristics =
            (struct ble_gatt_chr_def[]){
                {.uuid = &rx_uuid.u, .access_cb = on_access, .flags = BLE_GATT_CHR_F_WRITE},
                {.uuid = &tx_uuid.u, .access_cb = on_access, .flags = BLE_GATT_CHR_F_NOTIFY, .val_handle = &tx_handle},
                {0},
            },
    },
    {0},
};

// the service data names the dongle to its phone, zeros mean that it has no phone yet
static void advertise(void)
{
    uint8_t data[UUID_SIZE + ID_SIZE] = {UUID(0)};
    if (paired) {
        uint8_t hash[KEY_SIZE];
        size_t written;
        psa_hash_compute(PSA_ALG_SHA_256, secret, SECRET_SIZE, hash, sizeof(hash), &written);
        memcpy(data + UUID_SIZE, hash, ID_SIZE);
    }
    struct ble_hs_adv_fields fields = {
        .flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP,
        .svc_data_uuid128 = data,
        .svc_data_uuid128_len = sizeof(data),
    };
    struct ble_gap_adv_params params = {.conn_mode = BLE_GAP_CONN_MODE_UND, .disc_mode = BLE_GAP_DISC_MODE_GEN};
    ble_gap_adv_set_fields(&fields);
    ble_gap_adv_start(address_type, NULL, BLE_HS_FOREVER, &params, on_gap, NULL);
}

static int on_gap(struct ble_gap_event *event, void *arg)
{
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status != 0) {
            advertise();
            break;
        }
        connection = event->connect.conn_handle;
        memset(&session, 0, sizeof(session));
        incoming.size = 0;
        pairing = false;
        esp_timer_start_once(auth_timer, AUTH_TIMEOUT_US);
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        connection = BLE_HS_CONN_HANDLE_NONE;
        session.ready = false;
        session.keyed = false;
        esp_timer_stop(auth_timer);
        on_disconnect();
        advertise();
        break;
    case BLE_GAP_EVENT_ADV_COMPLETE:
        advertise();
        break;
    }
    return 0;
}

static void on_auth_timeout(void *arg)
{
    if (connection != BLE_HS_CONN_HANDLE_NONE && !session.ready) {
        ble_gap_terminate(connection, BLE_ERR_REM_USER_CONN_TERM);
    }
}

static void on_sync(void)
{
    ble_hs_id_infer_auto(0, &address_type);
    advertise();
}

static void host_task(void *arg)
{
    nimble_port_run();
    nimble_port_freertos_deinit();
}

void link_start(void)
{
    const esp_timer_create_args_t timer = {.callback = on_auth_timeout, .name = "auth"};
    paired = store_secret(secret);
    sending = xSemaphoreCreateMutex();
    ESP_ERROR_CHECK(esp_timer_create(&timer, &auth_timer));
    ESP_ERROR_CHECK(psa_crypto_init());
    ESP_ERROR_CHECK(nimble_port_init());
    ble_hs_cfg.sync_cb = on_sync;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ESP_ERROR_CHECK(ble_gatts_count_cfg(services));
    ESP_ERROR_CHECK(ble_gatts_add_svcs(services));
    nimble_port_freertos_init(host_task);
}
