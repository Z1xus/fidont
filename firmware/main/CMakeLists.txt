idf_component_register(
    SRCS "main.c" "usb.c" "link.c" "store.c" "light.c"
    INCLUDE_DIRS "."
    PRIV_REQUIRES bt nvs_flash esp_partition app_update mbedtls esp_timer
)
