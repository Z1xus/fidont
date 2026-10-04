#include <string.h>

#include "esp_app_desc.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "mbedtls/ecdh.h"
#include "mbedtls/gcm.h"
#include "mbedtls/hkdf.h"
#include "mbedtls/sha256.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
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

static int random_bytes(void *context, unsigned char *buffer, size_t size)
{
    esp_fill_random(buffer, size);
    return 0;
}

static void derive(const uint8_t *key, const uint8_t *salt, size_t salt_size, const char *info, uint8_t *output,
                   size_t size)
{
    mbedtls_hkdf(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), salt, salt_size, key, KEY_SIZE, (const uint8_t *)info,
                 strlen(info), output, size);
}

static bool crypt(int mode, const uint8_t *key, uint32_t count, uint8_t *data, size_t size, uint8_t *tag)
{
    uint8_t nonce[NONCE_SIZE] = {[8] = count >> 24, count >> 16, count >> 8, count};
    mbedtls_gcm_context gcm;
    mbedtls_gcm_init(&gcm);
    mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, key, KEY_SIZE * 8);
    int result =
        mode == MBEDTLS_GCM_ENCRYPT
            ? mbedtls_gcm_crypt_and_tag(&gcm, mode, size, nonce, NONCE_SIZE, NULL, 0, data, data, TAG_SIZE, tag)
            : mbedtls_gcm_auth_decrypt(&gcm, size, nonce, NONCE_SIZE, NULL, 0, tag, TAG_SIZE, data, data);
    mbedtls_gcm_free(&gcm);
    return result == 0;
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
    bool sent = session.keyed &&
                crypt(MBEDTLS_GCM_ENCRYPT, session.tx_key, session.sent++, outgoing + FRAME_HEADER, 1 + size,
                      outgoing + FRAME_HEADER + 1 + size) &&
                notify(outgoing, FRAME_HEADER + body);
    xSemaphoreGive(sending);
    return sent;
}

bool link_send(uint8_t type, const uint8_t *data, size_t size) { return session.ready && send(type, data, size); }

// both sides make a key pair, and the secret comes from the shared point
static bool pair(const uint8_t *phone_key, uint8_t *dongle_key)
{
    mbedtls_ecp_group group;
    mbedtls_ecp_point public, peer;
    mbedtls_mpi private, shared;
    mbedtls_ecp_group_init(&group);
    mbedtls_ecp_point_init(&public);
    mbedtls_ecp_point_init(&peer);
    mbedtls_mpi_init(&private);
    mbedtls_mpi_init(&shared);

    uint8_t point[KEY_SIZE];
    uint8_t salt[2 * POINT_SIZE];
    size_t written;
    bool done = mbedtls_ecp_group_load(&group, MBEDTLS_ECP_DP_SECP256R1) == 0 &&
                mbedtls_ecp_point_read_binary(&group, &peer, phone_key, POINT_SIZE) == 0 &&
                mbedtls_ecp_check_pubkey(&group, &peer) == 0 &&
                mbedtls_ecdh_gen_public(&group, &private, &public, random_bytes, NULL) == 0 &&
                mbedtls_ecp_point_write_binary(&group, &public, MBEDTLS_ECP_PF_UNCOMPRESSED, &written, dongle_key,
                                               POINT_SIZE) == 0 &&
                mbedtls_ecdh_compute_shared(&group, &shared, &peer, &private, random_bytes, NULL) == 0 &&
                mbedtls_mpi_write_binary(&shared, point, sizeof(point)) == 0;
    if (done) {
        memcpy(salt, phone_key, POINT_SIZE);
        memcpy(salt + POINT_SIZE, dongle_key, POINT_SIZE);
        derive(point, salt, sizeof(salt), "fidont pair", paired_secret, SECRET_SIZE);
    }

    mbedtls_ecp_group_free(&group);
    mbedtls_ecp_point_free(&public);
    mbedtls_ecp_point_free(&peer);
    mbedtls_mpi_free(&private);
    mbedtls_mpi_free(&shared);
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
    if (size <= TAG_SIZE ||
        !crypt(MBEDTLS_GCM_DECRYPT, session.rx_key, session.received++, body, size - TAG_SIZE,
               body + size - TAG_SIZE) ||
        (!session.ready && body[0] != LINK_INFO)) {
        ble_gap_terminate(connection, BLE_ERR_REM_USER_CONN_TERM);
        return;
    }
    if (!session.ready) {
        // the phone has shown that it holds the secret
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
        mbedtls_sha256(secret, SECRET_SIZE, hash, 0);
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
    ESP_ERROR_CHECK(nimble_port_init());
    ble_hs_cfg.sync_cb = on_sync;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ESP_ERROR_CHECK(ble_gatts_count_cfg(services));
    ESP_ERROR_CHECK(ble_gatts_add_svcs(services));
    nimble_port_freertos_init(host_task);
}
