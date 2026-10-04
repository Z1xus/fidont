# fidont

<picture><source media="(prefers-color-scheme: dark)" srcset="https://www.shieldcn.dev/github/downloads/Z1xus/fidont.svg?variant=secondary&amp;size=xs&amp;mode=dark"><img alt="Total downloads" src="https://www.shieldcn.dev/github/downloads/Z1xus/fidont.svg?variant=secondary&amp;size=xs&amp;mode=light"></picture>
<picture><source media="(prefers-color-scheme: dark)" srcset="https://www.shieldcn.dev/github/last-commit/Z1xus/fidont.svg?variant=secondary&amp;size=xs&amp;mode=dark"><img alt="Last commit" src="https://www.shieldcn.dev/github/last-commit/Z1xus/fidont.svg?variant=secondary&amp;size=xs&amp;mode=light"></picture>

use your android phone as a fido2/webauthn key

| where | how |
| --- | --- |
| apps and sites on the phone | passkey provider |
| a browser on another device | scan its passkey qr code in the app |
| nfc readers | tap the phone |
| anything that only takes a usb key | the [dongle](#dongle) |

keys are made in strongbox (or the tee if the phone has none) and every signature needs your fingerprint or pin.  
but they never leave the phone, so there's no sync or backup, and removing the screen lock deletes them. so keep a second key on anything important

> [!NOTE]
> fido2/webauthn compatible, but not fido certified. sites that only accept certified keys will reject it

> [!WARNING]
> this is early and hasn't been tested on real hardware yet (the dongle firmware has only run in qemu)

## install

needs android 14 or newer. grab the apk from [releases](https://github.com/Z1xus/fidont/releases) and install it. then turn fidont on as a passkey provider (the app has a button for it)

## dongle

an esp32-s3 board that shows up as a usb security key and passes every request to the phone over bluetooth. it holds no keys, so if you lose it you just flash another one

plug the board into the phone and tap `Set up`, the app flashes and pairs it in one go.  
if the board isn't found, hold BOOT while plugging it in

if flashing from the phone doesn't work, use the [web flasher](https://z1xus.github.io/fidont/) and then `Pair over Bluetooth` in the app

also the phone only talks to the dongle while the app is open, so open it when something asks for the key

## relay

the qr flow needs a relay between the phone and the computer, the app uses google's (`cable.ua5v.com`) for now

to run your own:

```sh
docker build -t fidont-relay relay
docker run -p 8080:8080 fidont-relay
```

then put it behind tls and set `RELAY` in [`Hybrid.kt`](core/src/commonMain/kotlin/us/z1x/fidont/hybrid/Hybrid.kt) to your id.  
browsers get the domain from the id, so you can't pick it yourself (260 is `cable.ahbeeuk74xtkd.com`)

## build

needs jdk 17+, the android sdk, go and docker. the firmware goes first because the app bundles it

```sh
docker run --rm -v "$PWD:/project" -w /project/firmware espressif/idf:v5.5.5 \
    idf.py build merge-bin -o ../../android/src/main/assets/firmware.bin
./gradlew :android:assembleDebug
go build -C relay
```
