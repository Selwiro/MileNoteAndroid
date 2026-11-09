# MileNote

![release](https://img.shields.io/github/v/release/Selwiro/MileNoteAndroid?include_prereleases&label=release&color=55758b)
![platform](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white)
![compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203%20Expressive-4285F4?logo=jetpackcompose&logoColor=white)

[简体中文](README.md) · **English**

> **One car, one ledger.** Turn fuel, maintenance and every kilometre into a ledger you can actually read.

MileNote is a **native Android, local-first, multi-vehicle car ledger**: record nine expense categories, compute real fuel economy with the full-tank method (kWh/100 km for EVs), and manage maintenance by whichever comes first — distance or date. **No account, no backend** — your ledger lives on your phone; when you need it on another device, sync it to your own WebDAV server.

[Website & download](https://selwiro.github.io/MileNoteAndroid/en/) · [中文站](https://selwiro.github.io/MileNoteAndroid/) · [Releases](https://github.com/Selwiro/MileNoteAndroid/releases) · [Issues](https://github.com/Selwiro/MileNoteAndroid/issues)

<details>
<summary><b>简体中文</b></summary>

里程志（MileNote）是一款 Android 原生、本地优先的多车用车账本：九类费用、满箱法油耗（纯电按满电法算电耗）、12/6 项保养方案。无账号、无后端，账本默认只存在手机里；需要跨设备时可同步到自己的 WebDAV。完整中文说明见 [README.md](README.md)。

</details>

## Screenshots

> Captured from the built-in demo ledger (English UI, 0.32.0); all data is synthetic and only illustrates the information structure. Latest UI on the [website](https://selwiro.github.io/MileNoteAndroid/en/#screenshots).

| Dashboard | New record | Statistics | Maintenance |
|:---:|:---:|:---:|:---:|
| <img src="https://raw.githubusercontent.com/Selwiro/MileNoteAndroid/gh-pages/assets/screens-en/dashboard.webp" width="180" alt="Dashboard"> | <img src="https://raw.githubusercontent.com/Selwiro/MileNoteAndroid/gh-pages/assets/screens-en/editor.webp" width="180" alt="New record"> | <img src="https://raw.githubusercontent.com/Selwiro/MileNoteAndroid/gh-pages/assets/screens-en/analytics.webp" width="180" alt="Statistics"> | <img src="https://raw.githubusercontent.com/Selwiro/MileNoteAndroid/gh-pages/assets/screens-en/maintenance.webp" width="180" alt="Maintenance"> |

## Features

- **Multi-vehicle ledger** — Any number of vehicles; expenses, fuel economy, maintenance, imports and exports are scoped to the current vehicle, while complete backups cover every vehicle. Vehicle photos from the gallery (compressed automatically) or an image URL.
- **Quick entry** — Nine built-in categories: fuel, insurance, maintenance, repairs, detailing, tolls, parking, fines and car supplies, plus custom categories. The category icon grid sits on top and amount, date, odometer and energy fields fit on one screen; search, filter, group by month, edit and delete.
- **Fuel economy / energy use** — The full-tank method only counts between two full tanks: the first full tank sets the baseline, partial fills carry over to the next settlement, and marking a missed fill-up resets the baseline. Outputs average economy, fuel cost per kilometre, trends and fill-up details. EVs switch to an energy view (kWh/100 km, full-charge method; when no full-charge interval exists it falls back to cumulative charging with a clear note).
- **Service plans** — 12 plans for combustion cars and 6 for EVs; a plan moves to the to-do list when either distance or date falls due. The plan table shows last done, next date/mileage and status; completing one records it and schedules the next round.
- **Statistics** — Total spend, recorded distance, cost per kilometre, average fuel economy; stacked monthly bars for trends and a donut for cost composition; statistics / records tabs and custom date ranges.
- **Import & export** — Bear (XLS/XLSX), Qianji (TXT/JSON/CSV/XLSX) and full JSON backups; field mapping, row-by-row preview, duplicate skipping, and system-share intake. Expenses export to CSV (17 columns) for the current vehicle; the full ledger exports to JSON.
- **Receipt scanning** — CameraX capture or gallery pick; on-device ML Kit (Chinese) reads the amount paid, fuel volume and date. Only empty fields are filled, results need review, and images are never written to disk or uploaded.
- **Optional WebDAV sync** — Nutstore (Jianguoyun), Nextcloud, Synology or your own WebDAV; foreground auto-sync (once on open plus a debounce after local edits) and manual sync, stopping when the app closes. The connection password is encrypted with Android Keystore + AES-256-GCM.
- **Today's fuel price** — Shows the current price for the vehicle's fuel grade in your located city and the next adjustment direction; for reference only (hidden for EVs and in the English UI).
- **Look & language** — Material 3 Expressive; five themes (Mile Blue, Forest Green, Lilac, Warm Amber, Graphite) × light/dark; English and Chinese UI; currency / distance / volume settings change labels only, never values.
- **Native integration** — Home-screen shortcuts (new entry / scan receipt), system share intake, SAF file picking, update check; portrait-locked UI.

## Data & privacy

- **No account** — No phone number, email or third-party login; no subscriptions, ads or analytics.
- **Ledger stays local** — A single `version:2` JSON file in the app's private storage (vehicle images embedded as data URLs), written atomically. It never passes through a developer server.
- **On-device computation** — File parsing and OCR run on the device; original files and OCR images are never uploaded or written into the ledger.
- **Three permissions only** — Camera, network and coarse location (location is used solely for the fuel price card).
- **WebDAV is your server** — Address and password are supplied by you; Android uploads plain JSON and release builds only allow HTTPS. Sync only runs in the foreground.
- **Keep your own backups** — Export a full JSON backup before uninstalling, switching devices or clearing data; backups include vehicle images and the whole ledger.

## Download & install

Current version **0.35.3 (beta, versionCode 92)**; the first stable release is planned as 1.0.0. The latest APK, release notes and SHA-256 are on [Releases](https://github.com/Selwiro/MileNoteAndroid/releases); the website download page reads the latest release automatically.

- Android 8.0 (API 26) and above
- arm64-v8a / armeabi-v7a; APK around 23 MB
- Self-signed APK — allow "install unknown apps" on first install

After installing, try the built-in demo ledger (two vehicles and complete sample data; it resets on exit and never touches your real ledger), then add a vehicle, record the first entry and export a full JSON backup. Builds signed with different keys cannot overwrite each other; export a backup before uninstalling or switching devices.

## Build from source

Requires **JDK 17+** and **Android SDK Platform 37 / Build-Tools 37.0.0**. Gradle comes from the repository wrapper (9.6.0, downloaded on first build) — no global Gradle needed. Point `ANDROID_HOME` at the SDK, or let Android Studio generate `local.properties` when you open the repository.

```bash
git clone git@github.com:Selwiro/MileNoteAndroid.git
cd MileNoteAndroid

./gradlew :app:assembleDebug                     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :domain:test :app:testDebugUnitTest    # 244 domain + 116 app unit tests
./build.sh release                               # optional: R8 + signing (when a key is present), archived to dist/ with .sha256
```

You can also open the repository root in Android Studio and run `app`.

- **Mainland China network**: `export MILENOTE_ALIYUN=1` prefers the Aliyun mirror; if the wrapper distribution downloads slowly, replace `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` with a mirror (for example `https://mirrors.cloud.tencent.com/gradle/gradle-9.6.0-bin.zip`).
- **Release signing is optional**: `keystore/` and password files are never committed. Put the key and `keystore/keystore.properties` (`storePassword` / `keyAlias` / `keyPassword`) in place, or set `MILENOTE_STORE_PASSWORD` / `MILENOTE_KEY_PASSWORD`, and release builds get signed; otherwise you get `app-release-unsigned.apk`. Anyone can still clone and build.
- `build.sh` archives to `dist/` by default; override with `MILENOTE_OUT_DIR=<dir>`.

## Tech stack & structure

Kotlin + Jetpack Compose + Material 3 Expressive; a single Activity with Compose Navigation; the ledger is a single JSON snapshot (DataStore for settings); on-device ML Kit OCR; OkHttp for WebDAV.

| Module | Contents |
|---|---|
| `app/` | Compose UI and ViewModel, data layer (ledger I/O), OCR, import/export, WebDAV, platform capabilities (SAF / share / location) |
| `domain/` | Pure Kotlin domain layer: models, full-tank / full-charge methods, service plans, statistics, backup merging, import parsing (JVM unit tests) |
| `app/src/test`, `domain/src/test` | 116 app tests and 244 domain tests |
| `build.sh`, `dev-gradle.sh` | Build, signing and archive scripts |

Version matrix (tested together; avoid casual upgrades): Gradle 9.6.0 · AGP 9.5.0 · Kotlin 2.4.21 · Compose BOM 2026.09.00 · material3 1.5.0-beta02 · compileSdk / targetSdk 37 · minSdk 26.

## Versions & releases

`main` keeps moving; each `vX.Y.Z` tag maps to a GitHub Release (APK + `.sha256`). Beta versions use `0.x`; the first stable release is `1.0.0`, followed by semver. This repository's history starts at `v0.27.0` (2026-11-06). See [Releases](https://github.com/Selwiro/MileNoteAndroid/releases) for changelogs.

## Known limitations

- No account system or real-time multi-device sync; WebDAV sync runs in the foreground only and the remote ledger is plain JSON.
- Service due notices appear only in the app's to-do list — no system notifications; a single reminder can be exported as an `.ics` calendar file.
- Portrait-locked; no tablet / landscape two-pane layout.
- The APK is self-signed; allow unknown sources on first install, and changing the signing key prevents overwrite-installing.
- EVs do not get receipt OCR for charging, battery health or range estimates; the fuel-price adjustment direction is indicative only.
- Cost per kilometre = recorded spending ÷ recorded distance, excluding depreciation, loan interest and other unrecorded costs.

## Feedback & contributing

- **Bugs and ideas**: open an [Issue](https://github.com/Selwiro/MileNoteAndroid/issues); include the version (Settings → About), device model, steps to reproduce and screenshots where useful.
- **Pull requests**: please open an Issue to discuss first; before submitting, run `./gradlew :domain:test :app:testDebugUnitTest` and `./gradlew :app:assembleDebug`.
- **Conventions**: [AGENTS.md](AGENTS.md) documents the hard rules (ledger format, permissions, tests, releases); commit messages follow `type(scope): summary`.
- **Privacy**: use synthetic sample data only; never commit real receipts, real ledgers, keys or build artifacts.

## Links

- Website / download: <https://selwiro.github.io/MileNoteAndroid/en/> (Chinese: <https://selwiro.github.io/MileNoteAndroid/>)
- Releases: <https://github.com/Selwiro/MileNoteAndroid/releases>
- Issues: <https://github.com/Selwiro/MileNoteAndroid/issues>
- Website source and screenshots live in the `gh-pages` branch; `main` keeps Android code only.
