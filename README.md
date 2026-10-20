# HomeTv Remote

An Android phone app that turns your device into a fully featured remote control
for Android TV, speaking the official **Android TV Remote Protocol v2** directly
over your local Wi-Fi network.

No cloud account, no MQTT broker, no internet access required — everything stays
on your LAN.

---

## Features

- **Automatic TV discovery** via mDNS / NSD (`_androidtvremote2._tcp`) with
  sequential service resolution and retry on DNS failure.
- **Secure pairing** using the Android TV Remote v2 handshake:
  - Ephemeral self-signed TLS client certificate (AndroidKeyStore).
  - 6-character hex pairing code shown on the TV screen.
  - SHA-256 shared-secret derivation over both RSA moduli/exponents and the
    last 4 characters of the pairing code.
- **Low-latency key injection** over the TLS command channel (port 6466):
  - D-pad navigation (a large oval pad split into four tappable quadrants +
    a center OK button).
  - Home, Back, Power.
  - Volume Up / Down / Mute.
- **App launching** via Android app links: YouTube, Netflix, Prime Video.
- **Multiple TV support** — every paired TV is remembered (persisted as JSON in
  `SharedPreferences`, keyed by the TV certificate fingerprint) and can be
  reconnected with one tap.
- **Manual IP entry** for TVs that do not respond to mDNS.
- **Optional ADB transport** (advanced) — for TVs where the official Remote
  Service is unavailable but ADB over TCP is enabled, using
  [dadb](https://github.com/dadb/dadb).
- **Devices bottom sheet** — device list, add-by-IP, transport toggle, disconnect
  and diagnostic-log toggle all live in one sheet, keeping the main remote
  screen clean.
- **Diagnostic log panel** — timestamped protocol/connection events, hidden by
  default, toggled from the Devices sheet.

## Requirements

| Item | Value |
| --- | --- |
| Phone app | Android 8.0+ (minSdk 26), tested up to Android 16 (targetSdk 36) |
| TV | Any Android TV / Google TV with **Android TV Remote Service** enabled |
| Network | Phone and TV on the same Wi-Fi / LAN |
| TV setting | *Settings → Remotes & Accessories → Android TV Remote Service* must be ON |

## Getting the TV ready

1. On the TV, open **Settings → Remotes & Accessories** and enable
   **Android TV Remote Service** (or "Network debugging" / "Remote Debugging"
   for the ADB fallback).
2. Note the TV's IP address (Settings → Network → Status) if you prefer manual
   entry over mDNS discovery.

## Building

```bash
# from the repository root
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:assembleRelease      # release APK
./gradlew :app:testDebugUnitTest    # unit tests (protocol + pairing crypto)
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.
Install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Note:** if Android Studio is your only JDK, point Gradle at its embedded JBR:
> `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew ...`

## Releases

CI builds and publishes a signed release APK. Push a tag like `v1.0.0`, or run
the **Release** workflow manually from the Actions tab. The workflow runs the
unit tests, builds `app-release.apk` and attaches it (plus a `.sha256`
checksum) to a GitHub Release named `HomeTv <version>`.

The version comes from the tag (`v1.0.0` → `1.0.0`) or the manual input;
`versionCode` is the CI run number.

The build is signed with a release keystore stored as repository secrets
(*Settings → Secrets and variables → Actions*):

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | `base64` of the `.keystore`/`.jks` file |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias |
| `SIGNING_KEY_PASSWORD` | Key password |

> Modern `keytool` produces PKCS12 keystores, which require the key password to
> be **identical** to the store password — otherwise packaging fails with
> `Given final block not properly padded`.

Create and export a keystore once:

```bash
keytool -genkeypair -v -keystore release.keystore -alias hometv \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -i release.keystore | pbcopy    # paste into SIGNING_KEYSTORE_BASE64
```

Without these secrets a local `./gradlew :app:assembleRelease` still works —
it just produces an unsigned APK.

## Using the app

1. Launch **HomeTv** and grant the nearby-devices permission when prompted.
2. Tap **Scan** (or open the **Devices** sheet, which auto-scans) to discover
   Android TVs on your LAN. Discovered TVs appear in the Devices sheet.
3. Select a TV, then tap **Connect**. On first use the app starts a **pairing**
   session instead — the TV displays a 6-character hex code; type it into the
   pairing dialog and submit.
4. Once paired, the header shows **Connected** and the remote becomes active:
   - **D-pad oval** — tap any of the four quadrants to navigate; tap the center
     **OK** circle to select.
   - **POWER / HOME / BACK** row.
   - **VOL− / MUTE / VOL+** row.
   - **YouTube / Netflix / Prime** app-launch buttons.
5. Volume and power state changes reported by the TV stream back into the
   diagnostic log.
6. To switch TVs, open **Devices (n)**; to forget a TV, select it and tap
   **Disconnect**, then remove it from the list. To add a TV manually, use
   **Add by IP**.

## Architecture

```
app/src/main/java/com/example/hometv/
├── MainActivity.kt                 # entry point, edge-to-edge, permissions
├── data/
│   ├── network/
│   │   ├── NsdDiscoveryManager.kt  # mDNS discovery (_androidtvremote2._tcp)
│   │   └── AdbTransport.kt         # optional ADB-over-TCP transport (dadb)
│   ├── pairing/
│   │   ├── PairingManager.kt       # pairing state machine (ports 6467/6466)
│   │   ├── PairingSecret.kt        # SHA-256 shared-secret derivation
│   │   └── PairedTvStore.kt        # JSON persistence of paired TVs
│   ├── protocol/
│   │   └── ProtoCodec.kt           # Protocol Buffers wire codec (hand-rolled)
│   ├── remote/
│   │   └── AndroidTvV2Transport.kt # TLS command session, ping, reconnect
│   ├── repository/
│   │   └── TvRemoteRepositoryImpl.kt# orchestration: discovery+pairing+transport
│   └── security/
│       └── TvClientIdentity.kt     # AndroidKeyStore self-signed client cert
├── domain/
│   ├── model/                      # TvDevice, RemoteCommand, enums
│   └── repository/                 # TvRemoteRepository interface
└── ui/
    ├── remote/                     # RemoteScreen (Compose), ViewModel, UiState
    └── theme/                      # Material 3 dark theme
```

### Protocol summary

| Step | Port | Transport |
| --- | --- | --- |
| mDNS service browse | — | `_androidtvremote2._tcp` |
| Pairing handshake | **6467** | TLS (server cert + ephemeral client cert) |
| Command / event channel | **6466** | TLS after secret verification |

- Features advertised: `PING | KEY | POWER | VOLUME | APP_LINK`.
- Key events are sent as direction `SHORT (3)` presses with the standard Android
  keycode in field 10.
- App launches use field 90 with the target app-link URI.
- The transport keeps a read loop for server-pushed events (volume, power) and
  reconnects automatically with exponential backoff if the socket drops.

## Tests

Unit tests cover the two pieces with real logic:

- `ProtoCodecTest.kt` — encoding/decoding of pairing, configuration and key
  frames.
- `PairingSecretTest.kt` — SHA-256 secret derivation against known vectors.

```bash
./gradlew :app:testDebugUnitTest
```

## Privacy

- The app communicates **only** with TVs on your local network.
- It does **not** collect, transmit, or store any data outside the device.
- Paired-TV records (name, IP, ports, fingerprint, timestamps) are stored in
  app-private `SharedPreferences` and cleared when you forget a TV or clear
  app data.
- The only network permissions are for LAN discovery and sockets
  (`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
  `CHANGE_WIFI_MULTICAST_STATE`, `NEARBY_WIFI_DEVICES`).

## Third-party acknowledgements

The Android TV Remote v2 protocol implementation is adapted from
[`megarohas/megarohas_tv_voice`](https://github.com/megarohas/megarohas_tv_voice)
(MIT). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

This project also uses [dadb](https://github.com/dadb/dadb) for the optional
ADB transport.

## License

See repository owner for licensing terms. Protocol reference code is MIT —
see `THIRD_PARTY_NOTICES.md`.
