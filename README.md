# PocketRig

An on-device **Monero (XMR) miner** for Android with an adaptive thermal & battery
governor. It runs RandomX natively under a foreground service (so it survives screen
off) and scales itself from 1 core to the whole chip based on live temperature, screen,
and battery signals — using your phone hard only when it's safe to.

> Mining a phone earns pennies and makes heat. PocketRig isn't about profit; it's about
> doing it **safely and transparently** on hardware you own. Have fun with it.

- **Min Android:** 8.0 (API 26) · **ABI:** arm64-v8a · **License:** GPLv3
- **Privacy:** no analytics, no trackers, no ads, no web fonts/CDNs. The app phones home
  to nothing except the mining pool you pick.

## Install

- **Obtainium** (recommended — auto-updates from GitHub Releases): install
  [Obtainium](https://github.com/ImranR98/Obtainium), then **Add App** and paste
  `https://github.com/JackKnifeAI/PocketRig`. It tracks every release automatically.
- **Our F-Droid repo:** add the repo URL to F-Droid / Droid-ify / Neo Store for one-tap
  updates — _URL published below once live._
- **GitHub Releases:** grab the latest signed `PocketRig.apk` from the
  [Releases](../../releases) page.
- **Phone-to-phone:** any installed copy can share itself — tap **Share App**, scan the
  QR from the other phone (same Wi-Fi), install.

On first launch, allow it to ignore battery optimization (this is what lets mining
continue with the screen off) and grant the notification permission.

## Using it

1. **Wallet** — paste your own Monero address, or tap the scanner to read a wallet QR
   from the camera or a screenshot. A fresh install ships with **no wallet** — mining
   won't start until you set one.
2. **Pool** — MoneroOcean, SupportXMR, HashVault, Nanopool, 2Miners, or a custom
   `host:port`.
3. **Mode** — `2` / `4` / `6` / `8` fixed cores, or **Adaptive**.
4. **Adaptive** (the good part): screen off → ramps up; pick the phone up → drops to
   1–2 cores so it stays snappy; chip warms → backs off; battery low & unplugged → eases
   or pauses. Gentle ramp up, immediate throttle down. The prime cores are reserved for
   the system.
5. **Auto-start on charge** (optional, **off by default**) — start mining automatically
   when you plug in.

## Developer fee (disclosed)

For about **40 seconds out of every 30 minutes of mining (~2.2%)**, hashes go to the
developer's wallet instead of yours. This is the same model **xmrig** itself ships with.
It is shown in the app while it's active, and it's a permanent, disclosed part of the
app — not a bug and not hidden.

**Developer wallet / donation address:**
`47L3SgYxj5UhnFkHS6roYdirv159MbimSAd5cjBDjLyX5r58rrKFuR2RPdrSBUj8LRGehd9RBdzpqbyXAKZvVGF66E4y7o7`

## Permissions & why

| Permission | Why |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | Connect to the mining pool. |
| `FOREGROUND_SERVICE` / `WAKE_LOCK` | Keep mining with the screen off. |
| `POST_NOTIFICATIONS` | The persistent mining notification. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask (optionally) to keep running in the background. |
| `RECEIVE_BOOT_COMPLETED` / power-connected receiver | Optional auto-start on charge. |
| `CAMERA` (feature not required) | Scan a wallet QR. Never used otherwise. |
| `VIBRATE` | Haptic feedback. |

Cleartext traffic is enabled because most Monero pools use non-TLS stratum ports; a Tor
(Orbot SOCKS5) toggle is available for pool traffic.

## How it's built

```
APK (com.pocketrig.miner)
├─ MainActivity      WebView hosting assets/ui/index.html + a JS bridge
├─ MinerService      foreground service + wakelock = the adaptive governor
│    ├─ spawns lib/arm64-v8a/libxmrig.so  (native RandomX miner)
│    ├─ reads thermal / BatteryManager / screen broadcasts
│    ├─ decides core count, applies live via xmrig's local HTTP API
│    └─ publishes status JSON the WebView renders
├─ ApkServer         tiny local HTTP server for phone-to-phone sharing
└─ lib/arm64-v8a/    libxmrig.so + libc++_shared.so
```

The QR encoder (`assets/ui/qr.js`) and decoder (`assets/ui/jsQR.js`) are bundled — the
app never contacts a QR web service. There are no Google Fonts or CDNs; everything the
UI needs ships inside the APK.

**Build the app:** `bash build_apk.sh` (see [BUILDING.md](BUILDING.md), which also covers
compiling the `libxmrig.so` payload from source — xmrig is GPLv3).

## License

GPLv3 — see [LICENSE](LICENSE). PocketRig bundles [xmrig](https://github.com/xmrig/xmrig)
(GPLv3); this project is therefore distributed under the same license.
