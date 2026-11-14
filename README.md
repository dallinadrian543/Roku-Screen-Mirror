# Roku Screen Mirroring

Android AirPlay sender (`com.dhatech.roku.cast`). Mirrors your phone's screen — and plays its audio — on Roku TVs and streaming players, Apple TVs, Macs, and AirPlay-compatible receivers.

## Warning!!!

- Requires sideloading debug signed apk!!!
- This code is modified and the apks are created using the gemini ai studio.

## Features

- **Multi-layer Roku & AirPlay discovery** on the local Wi-Fi network:
  - System mDNS (`NsdManager`) + `JmDNS` for `_airplay._tcp` and `_raop._tcp`
  - Roku ECP SSDP discovery (`239.255.255.250:1900`, `ST: roku:ecp`) and `/query/device-info` enrichment (port `8060`)
  - Active LAN `/24` subnet scan for networks that block multicast
  - uxplay-style BLE AirPlay beacon scanning and saved-endpoint fast probing
- **Automatic AirPlay port selection**: probes candidate AirPlay ports (`7000`, `7100`, `5000`, `49152`, `7001`), automatically selects the active mirroring port, and supports auto-port detection (or manual `host:port`) when connecting directly by IP
- **Screen + Audio casting**: long-press a receiver to choose screen with sound, screen without sound, or sound only
- **Video call & audio routing controls**: low-latency streaming mode, microphone mixing, and output device selection
- **One-time HAP/SRP pairing**: enter the PIN or password once and reconnect seamlessly
- **Adjustable quality**: resolution presets up to 4K UHD, frame rate, and VBR/CBR/CQ bitrate controls up to 200 Mbps
- **Quick Settings tile & diagnostic logs**: start casting from the shade and export log bundles

## Requirements

- Android 10 or later (`minSdk 29`, `targetSdk 36`)
- A physical Android device and an AirPlay / Roku receiver (with **Apple AirPlay and HomeKit** enabled in Roku Settings) on the same Wi-Fi network

## Building

Debug builds integrate LeakCanary for leak detection; release builds use R8 full-mode code and resource shrinking, 16 KB ELF/ZIP-aligned native libraries, strict Android Lint, and App Bundle (`AAB`) packaging configuration.

```sh
./gradlew :app:assembleDebug
./gradlew :app:bundleRelease
./gradlew test detekt :app:lintDebug
```

## Acknowledgements

Built on open-source AirPlay and protocol research:

- [kite](https://github.com/itsHenry35/Kite) — original source code modified for this forked project
- [doubletake](https://github.com/omarroth/doubletake) — Go AirPlay sender; modern session flows are Kotlin ports of it
- [UxPlay](https://github.com/FDH2/UxPlay) — open-source AirPlay receiver; a key reference during development
- [pyatv](https://github.com/postlund/pyatv) — AirPlay 2 protocol reference
- [shairport-sync](https://github.com/mikebrady/shairport-sync) — pairing and timing reference
- [openairplay](https://github.com/openairplay/airplay-spec) — early protocol documentation

## License

Released under the GNU LGPL-3.0 — see [LICENSE](LICENSE). Ported files retain doubletake's license headers. Open-source attributions are also listed in the app under Settings → About.
