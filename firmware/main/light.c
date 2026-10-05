#include <string.h>

#include "esp_timer.h"
#include "led_strip.h"

#include "fidont.h"

// the RGB LED of the ESP32-S3 SuperMini and of the DevKitC-1
#define LED_GPIO 48
#define RMT_RESOLUTION 10000000
#define RAINBOW_PERIOD 12000000
#define SAVE_DELAY 2000000

enum mode { MODE_OFF, MODE_REQUESTS, MODE_ON, MODE_RAINBOW };

static led_strip_handle_t strip;
static uint8_t light[LIGHT_SIZE] = {MODE_REQUESTS, 0, 64, 43};
static int64_t changed;

void light_start(void)
{
    led_strip_config_t config = {
        .strip_gpio_num = LED_GPIO,
        .max_leds = 1,
        .led_model = LED_MODEL_WS2812,
        .color_component_format = LED_STRIP_COLOR_COMPONENT_FMT_GRB,
    };
    led_strip_rmt_config_t rmt = {.resolution_hz = RMT_RESOLUTION};
    led_strip_new_rmt_device(&config, &rmt, &strip);
    store_light(light);
}

void light_set(const uint8_t value[LIGHT_SIZE])
{
    memcpy(light, value, LIGHT_SIZE);
    changed = esp_timer_get_time();
}

void light_tick(bool request)
{
    int64_t now = esp_timer_get_time();
    // the phone sends each step of a slider, the flash gets only the last one
    if (changed && now - changed > SAVE_DELAY) {
        store_set_light(light);
        changed = 0;
    }
    if (light[0] == MODE_RAINBOW) {
        uint8_t value = light[1] > light[2] ? light[1] : light[2];
        value = value > light[3] ? value : light[3];
        led_strip_set_pixel_hsv(strip, 0, now % RAINBOW_PERIOD * 360 / RAINBOW_PERIOD, 255, value);
    } else if (light[0] == MODE_ON || (light[0] == MODE_REQUESTS && request)) {
        led_strip_set_pixel(strip, 0, light[1], light[2], light[3]);
    } else {
        led_strip_set_pixel(strip, 0, 0, 0, 0);
    }
    led_strip_refresh(strip);
}
