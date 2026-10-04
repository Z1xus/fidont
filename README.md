# <img src="icon.svg" alt="" width="36" height="36" align="absmiddle"> fidont

<picture><source media="(prefers-color-scheme: dark)" srcset="https://www.shieldcn.dev/github/downloads/Z1xus/fidont.svg?variant=secondary&amp;size=xs&amp;mode=dark"><img alt="Total downloads" src="https://www.shieldcn.dev/github/downloads/Z1xus/fidont.svg?variant=secondary&amp;size=xs&amp;mode=light"></picture>
<picture><source media="(prefers-color-scheme: dark)" srcset="https://www.shieldcn.dev/github/last-commit/Z1xus/fidont.svg?variant=secondary&amp;size=xs&amp;mode=dark"><img alt="Last commit" src="https://www.shieldcn.dev/github/last-commit/Z1xus/fidont.svg?variant=secondary&amp;size=xs&amp;mode=light"></picture>

stop paying for yubikeys, use your android phone as a fido2/webauthn key instead

it works as:

- a passkey provider for apps and sites on the phone itself
- a key for browsers on other devices, you scan their passkey qr code with the app
- an nfc key, just tap the phone on a reader
- a usb key for everything else, through the [dongle](#dongle)

keys live in strongbox (or the tee), can't be extracted and only work with your fingerprint or pin.  
there is no sync, so if you lose the phone or remove the screen lock they are GONE, unless you turned on [backup](#backup) first

> [!NOTE]
> fido2/webauthn compatible, but not fido certified. sites that only accept certified keys will reject it

## install

needs android 14 or newer.  
grab the apk from [releases](https://github.com/Z1xus/fidont/releases) and install it

## dongle

an esp32-s3 board that shows up as a usb security key and passes every request to the phone over bluetooth.  
it holds no keys, so if you lose it you just flash another one

plug the board into the phone and tap `Set up`, the app flashes and pairs it in one go.  
and if the board isn't found, hold BOOT while plugging it in

if flashing from the phone doesn't work, use the [web flasher](https://z1xus.github.io/fidont/) and then `Pair over Bluetooth` in the app

also the phone only talks to the dongle while the app is open, so open it when something asks for the key

## backup

off by default, because a key that was made inside strongbox can't be copied out, not even by the app.  
if you turn on `Allow backup` in settings, new passkeys get made in the app and imported into strongbox instead, and the phone keeps an encrypted copy of each one that only opens with your fingerprint or pin

`Export` saves those copies to a file, encrypted with a password (pbkdf2 and aes-256-gcm) or plain if you leave the password empty.  
`Import` on another phone puts them into its keystore

passkeys from before you turned it on stay locked to the phone, so turn it on first if you want backups

## other password managers

fidont doesn't replace bitwarden (or google password manager, 1password etc), it does a different job.  
those sync your passkeys everywhere, fidont keeps them on one phone and works like a hardware key, so use both

android 14 lets you turn on more than one passkey provider, so they just show up next to each other when a site asks.  
what i'd actually do is keep the daily passkeys in the password manager and register fidont as the 2fa key for the password manager itself, and for the accounts you really care about

it also supports `hmac-secret` and prf, which is what bitwarden's passkey login and luks disk encryption through `systemd-cryptenroll` need

## relay

the qr flow needs a relay between the phone and the computer, the app uses mine at `cable.ahhkeysummo2d.com`.  
a relay only forwards encrypted messages, so it can't read them or sign in as you.  
mine keeps no logs and stores nothing, a tunnel just sits in memory for 2 minutes at most

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

the app has no accounts or analytics (or ads), and passkeys only leave the phone if you export them yourself.  
but the qr flow goes through my relay behind cloudflare, so both of us get to see your ip and when you connected.  
the messages themselves are e2ee and the relay doesn't log anything

## credits

- app: [jetpack compose, camerax and credentials](https://developer.android.com/jetpack), [kotlin](https://kotlinlang.org), [okhttp](https://square.github.io/okhttp), [sqldelight](https://sqldelight.github.io/sqldelight), [zxing](https://github.com/zxing/zxing), [material icons](https://fonts.google.com/icons)
- dongle firmware: [esp-idf](https://github.com/espressif/esp-idf), [tinyusb](https://github.com/hathach/tinyusb), [nimble](https://github.com/apache/mynewt-nimble), [mbed tls](https://github.com/Mbed-TLS/mbedtls)
- relay: [coder/websocket](https://github.com/coder/websocket)
- the qr flow is reimplemented from [chromium's hybrid transport](https://source.chromium.org/chromium/chromium/src/+/main:device/fido/cable/) and tested against [fenleon/passkey](https://github.com/fenleon/passkey)

made by [z1xus](https://z1x.us), licensed under [GPL-3.0](LICENSE)
