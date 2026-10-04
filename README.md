# <img src="icon.svg" alt="" width="36" height="36" align="absmiddle"> fidont

<picture><source media="(prefers-color-scheme: dark)" srcset="https://www.shieldcn.dev/github/downloads/Z1xus/fidont.svg?variant=secondary&amp;size=xs&amp;mode=dark"><img alt="Total downloads" src="https://www.shieldcn.dev/github/downloads/Z1xus/fidont.svg?variant=secondary&amp;size=xs&amp;mode=light"></picture>
<picture><source media="(prefers-color-scheme: dark)" srcset="https://www.shieldcn.dev/github/last-commit/Z1xus/fidont.svg?variant=secondary&amp;size=xs&amp;mode=dark"><img alt="Last commit" src="https://www.shieldcn.dev/github/last-commit/Z1xus/fidont.svg?variant=secondary&amp;size=xs&amp;mode=light"></picture>

stop paying for yubikeys, use your android phone as a fido2/webauthn key instead

it works as:

- a passkey provider for apps and sites on the phone itself
- a key for browsers on other devices, you scan their passkey qr code with the app
- an nfc key, just tap the phone on a reader
- a usb key for everything else, through the [dongle](#dongle)

keys live in strongbox (the tee if your phone doesn't have strongbox) and you can't get them out, the phone asks for your fingerprint or pin each time it signs something.  
but there is no sync and no backup either, so if you lose the phone or remove the screen lock the keys are GONE and you should really register a second key wherever you use this

> [!NOTE]
> fido2/webauthn compatible, but not fido certified. sites that only accept certified keys will reject it

> [!WARNING]
> this is early and hasn't been tested on real hardware yet (the dongle firmware has only run in qemu)

## install

needs android 14 or newer.  
grab the apk from [releases](https://github.com/Z1xus/fidont/releases) and install it.  
then the first launch walks you through turning it on as a passkey provider

## dongle

an esp32-s3 board that shows up as a usb security key and passes every request to the phone over bluetooth.  
it holds no keys, so if you lose it you just flash another one

plug the board into the phone and tap `Set up`, the app flashes and pairs it in one go.  
and if the board isn't found, hold BOOT while plugging it in

if flashing from the phone doesn't work, use the [web flasher](https://z1xus.github.io/fidont/) and then `Pair over Bluetooth` in the app

also the phone only talks to the dongle while the app is open, so open it when something asks for the key

## relay

the qr flow needs a relay between the phone and the computer, the app uses mine at `cable.ahhkeysummo2d.com`.  
a relay only forwards encrypted messages, so it can't read them or sign in as you

to run your own:

```sh
docker build -t fidont-relay relay
docker run -p 8080:8080 fidont-relay
```

browsers get the domain from a relay id (256 to 65535), so you can't pick it yourself.  
`docker run fidont-relay -domain <id>` prints the one for your id, register it and point it at the relay with tls in front.  
then set `RELAY` in [`Hybrid.kt`](core/src/commonMain/kotlin/us/z1x/fidont/hybrid/Hybrid.kt) to your id

## build

needs jdk 17+, the android sdk, go and docker.  
the firmware goes first because the app bundles it

```sh
docker run --rm -v "$PWD:/project" -w /project/firmware espressif/idf:v6.1 \
    idf.py build merge-bin -o ../../android/src/main/assets/firmware.bin
./gradlew :android:assembleDebug
go build -C relay
```

## privacy

the app has no accounts or analytics (or ads), and passkeys never leave the phone.  
but the qr flow goes through my relay behind cloudflare, so both of us get to see your ip and when you connected.  
the messages themselves are e2ee and the relay doesn't log anything

## credits

- app: [jetpack compose, camerax and credentials](https://developer.android.com/jetpack), [kotlin](https://kotlinlang.org), [okhttp](https://square.github.io/okhttp), [sqldelight](https://sqldelight.github.io/sqldelight), [zxing](https://github.com/zxing/zxing), [material icons](https://fonts.google.com/icons)
- dongle firmware: [esp-idf](https://github.com/espressif/esp-idf), [tinyusb](https://github.com/hathach/tinyusb), [nimble](https://github.com/apache/mynewt-nimble), [mbed tls](https://github.com/Mbed-TLS/mbedtls)
- relay: [coder/websocket](https://github.com/coder/websocket)
- the qr flow is reimplemented from [chromium's hybrid transport](https://source.chromium.org/chromium/chromium/src/+/main:device/fido/cable/) and tested against [fenleon/passkey](https://github.com/fenleon/passkey)

made by [z1xus](https://z1x.us), licensed under [GPL-3.0](LICENSE)
