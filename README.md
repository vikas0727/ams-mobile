# AMS Player (ams-mobile)

The Android TV signage player for the AMS API (`D:\Projects\AMS`). It pairs to a screen with a
6-character code, pulls the content manifest, plays it fullscreen from local disk, heartbeats
telemetry back, obeys remote commands, and uploads playback evidence — continuing through an outage
except for the parts that need the network.

Package `com.example.digi`, minSdk 24, targetSdk 36, Kotlin 2.0.21, Compose, Media3, AGP 8.11.

**Setup, build and release**
1. [Prerequisites](#1-prerequisites)
2. [First-time setup](#2-first-time-setup)
3. [`local.properties`](#3-localproperties)
4. [Keys and secrets](#4-keys-and-secrets)
5. [Build flavors and debug builds](#5-build-flavors-and-debug-builds)
6. [Release builds and signing](#6-release-builds-and-signing)
7. [Installing on the boxes](#7-installing-on-the-boxes)
8. [Releasing an update](#8-releasing-an-update)
9. [Troubleshooting](#9-troubleshooting)

**Reference:** 10 Payload encryption · 11 What is implemented · 12 Deployment on the Droidlogic boxes ·
13 What degrades · 14 Known gaps · 15 Layout

---

## 1. Prerequisites

- **Android Studio** (current stable) with Android SDK 36 — it brings the JDK 17 Gradle needs.
- The API project next to this one (`D:\Projects\AMS`) — the encryption keys are generated there.
- `adb` (Android SDK *platform-tools*) for installing on boxes.

## 2. First-time setup

```powershell
cd D:\Projects
git clone <repo-url> ams-mobile
cd ams-mobile
copy local.properties.example local.properties     # then fill it in (section 3)
```

Open the folder in Android Studio and let it sync (it writes `sdk.dir` into `local.properties`).

## 3. `local.properties`

Git-ignored, one per machine. Everything the build needs that differs per machine or environment:

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk

# API address per flavor — trailing slash and /api/v1 required
ams.dev.apiBaseUrl=http://10.0.2.2:5000/api/v1/          # emulator -> API on this PC
#ams.dev.apiBaseUrl=http://192.168.1.20:5000/api/v1/     # real box -> this PC's LAN IP
ams.uat.apiBaseUrl=https://<uat host>/api/v1/
ams.prod.apiBaseUrl=https://<prod host>/api/v1/

# Legacy (v1) encryption — only while the API has CRYPTO_LEGACY_ENABLED=true
digi.aesSecretKey=<the API's AES_SECRET_KEY, without quotes>
digi.aesIv=<the API's AES_IV, 32 hex characters>
digi.aesEnabled=true

# Optional override of the pinned API key (normally from crypto-server-keys.properties)
#ams.prod.cryptoServerKey=
```

- `ams.<flavor>.apiBaseUrl` wins; otherwise the older `digi.apiBaseUrl`; otherwise the built-in
  default (dev only — **UAT and PROD have no usable default**, so set them).
- **Release builds are HTTPS-only** (`src/main/res/xml/network_security_config.xml`). UAT/PROD URLs
  must be `https://`. Plain `http` works only in debug builds.
- Use the CMS host (the one nginx serves with `/api/` proxied) so the player uses the same address
  the CMS does.

## 4. Keys and secrets

| What | Where it comes from | In git? |
| --- | --- | --- |
| API public keys per flavor (payload encryption v2) | `crypto-server-keys.properties`, written by the API's key script | yes (public, not secret) |
| Legacy AES key / IV | The API's `.env.<env>` → `local.properties` | no |
| Release signing keystore | You create it once (section 6.1) | **never** |

Generate or refresh the API keys from the API project (PowerShell — call node directly):

```powershell
cd D:\Projects\AMS
node scripts/generate-crypto-keys.js --env prod --frontend ../AMS-Frontend --mobile ../ams-mobile
```

Use `--env uat` / `--env dev` for the others; `--force` makes new keys (then every player of that
environment must be rebuilt and reinstalled). Commit the updated `crypto-server-keys.properties`.
Details: API README §3.3.

## 5. Build flavors and debug builds

One flavor per environment. Each has its own API address, pinned API key, app name and `APP_ENV`;
the package name stays `com.example.digi` for all of them (so a box holds one at a time).

| Flavor | App name | Version suffix |
| --- | --- | --- |
| `dev` | AMS Player Dev | `-dev` |
| `uat` | AMS Player UAT | `-uat` |
| `prod` | AMS Player | — |

In Android Studio pick the variant in **Build ▸ Select Build Variant** (`devDebug`, `uatRelease`,
`prodRelease`, …). From the command line:

```powershell
.\gradlew assembleDevDebug                 # app\build\outputs\apk\dev\debug\app-dev-debug.apk
.\gradlew assembleUatDebug                 # debug build against UAT (allows http + user CAs)
.\gradlew test                             # unit tests: AES + v2 session cipher vectors, scheduler, schedule rules
adb install -r app\build\outputs\apk\dev\debug\app-dev-debug.apk
```

Then generate a pairing code for a screen in the CMS and enter it on the box (on-screen grid, D-pad
navigable). The Postman collection
`D:\Projects\AMS\postman\AMS_Player_Mobile_API.postman_collection.json` can mint a code without the CMS.

For a real box against your PC: allow inbound TCP 5000 in the Windows firewall and keep the box on
the same network — that is the usual first-pairing failure.

## 6. Release builds and signing

The release build type is minified (R8) and has **no signing config in Gradle**, so it must be
signed with your release key. Use the **same keystore for every release, forever** — Android refuses
to update an app signed with a different key.

### 6.1 Create the release keystore (once)

```powershell
keytool -genkeypair -v -keystore D:\Keys\ams-player-release.jks -alias ams-player `
  -keyalg RSA -keysize 2048 -validity 10000
```

(`keytool` is in Android Studio's JDK: `"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"`.)
It asks for a keystore password, a key password and a name. Keep the `.jks` **outside the repo**
and store it and both passwords in a password manager — a lost keystore means every box must
uninstall and re-pair to take a new build.

### 6.2 Build a signed APK — Android Studio (simplest)

1. **Build ▸ Generate Signed App Bundle / APK… ▸ APK ▸ Next**
2. Choose `D:\Keys\ams-player-release.jks`, alias `ams-player`, enter the passwords.
3. Build variant **`prodRelease`** (or `uatRelease`) ▸ **Create**.
4. The APK is in `app\prod\release\` (Android Studio shows a *locate* link).

### 6.3 Build a signed APK — command line

```powershell
.\gradlew assembleProdRelease
$bt = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0"          # your installed version
& "$bt\zipalign.exe" -v -p 4 app\build\outputs\apk\prod\release\app-prod-release-unsigned.apk app-prod-aligned.apk
& "$bt\apksigner.bat" sign --ks D:\Keys\ams-player-release.jks --ks-key-alias ams-player `
  --out ams-player-prod.apk app-prod-aligned.apk
& "$bt\apksigner.bat" verify --print-certs ams-player-prod.apk
```

Same for UAT with `assembleUatRelease` and the `uat` paths.

## 7. Installing on the boxes

**With adb** (box with network debugging on):

```powershell
adb connect <box-ip>:5555
adb install -r ams-player-prod.apk
```

**Download link:** host the APK on the **CMS** host, not the API host, e.g. copy it to the server's
CMS folder and link `https://<cms host>/downloads/ams-player.apk`:

```powershell
scp -i C:\keys\<server>.pem ams-player-prod.apk <user>@<server>:/var/www/ams-frontend/downloads/ams-player.apk
```

(Copy it again after each CMS deploy if your CMS deploy replaces the whole folder.)

**Switching a box between builds:** a debug build and a release build carry different signatures,
so moving a box from one to the other needs `adb uninstall com.example.digi` first, and the screen
must then be paired again.

Before the first PROD rollout: the API is deployed with its keys and legacy still on, the CMS is
deployed — then the players (API README §11).

## 8. Releasing an update

1. Raise `versionCode` (and `versionName`) in `app/build.gradle.kts` — Android will not install a
   build whose `versionCode` is not higher than the installed one.
2. Build and sign with the same keystore (section 6).
3. Install with `adb install -r …` or replace the APK at the download link. (`UPDATE_APP` from the
   CMS is not implemented — section 13.)

## 9. Troubleshooting

| Problem | Fix |
| --- | --- |
| Release build cannot reach the API | URL is `http://` or unset — release builds need `https://` in `ams.<flavor>.apiBaseUrl`. A URL ending in `.invalid` means the flavor's address was never set. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Signed with a different key than the installed app — uninstall first (then re-pair), or sign with the original keystore. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | Raise `versionCode`. |
| Every response fails to decrypt / handshake fails | Pinned key does not match the server: re-run the API key script for that environment, rebuild. Legacy: `digi.aesSecretKey`/`digi.aesIv` differ from the API's. |
| `426` from the API | The API has legacy off and this is an old build — install a current one. |
| Pairing never completes on a real box | Firewall / different network / wrong LAN IP. |
| Gradle sync fails on a new machine | `sdk.dir` missing in `local.properties` — open the project in Android Studio once. |

---

## 10. Payload encryption

**Current scheme (v2):** at start-up the player does a signed handshake with the API (`POST /api/v1/crypto/session`), checks the signature against the key pinned for its flavor, and then seals every request and opens every response with a per-session AES-256-GCM key (`SessionCipher`, `X-Crypto-Session` header). A `419` makes it handshake again on its own. The legacy static-key envelope described below is still used while the API has `CRYPTO_LEGACY_ENABLED=true` and no key is pinned.

### Legacy (v1)

`server.js` transparently AES-256-CBC encrypts every JSON response outside its bypass list, and
`/api/v1/player/*` is deliberately **not** in that list. So every player response arrives as
`{"data":"<hex>"}` and every request body has to go up the same way.

`CryptoInterceptor` handles both directions and the rest of the app never sees it. Two things it
gets right that are easy to get wrong:

- The multipart screenshot upload is **not** encrypted — multer parses that body before body-parser
  could ever see a `data` field.
- Plaintext responses are passed through untouched. The rate limiter, helmet and the decrypt
  middleware's own 400 all reply *before* the response encryptor runs, so unencrypted bodies are a
  normal thing to meet and must not be treated as corrupt.

If you later add `/api/v1/player` to the server's bypass list, set `digi.aesEnabled=false` and
nothing else changes.

---

## 11. What is implemented

### All eleven `/player/*` endpoints

| Endpoint | Where |
|---|---|
| `POST /player/pair` | `data/repo/PairingRepository.kt` — sends `deviceInfo` inline to save a round trip |
| `GET /player/me` | `PairingRepository.verify()` — run at start-up, before the first sync |
| `GET /player/sync` | `data/repo/ContentRepository.kt` |
| `POST /player/heartbeat` | `data/repo/HeartbeatRepository.kt` |
| `GET /player/commands` | `data/repo/CommandRepository.kt` |
| `POST /player/commands/:id/ack` | `CommandRepository.acknowledge()` |
| `POST /player/proof-of-play` | `data/repo/ProofOfPlayRecorder.kt` |
| `POST /player/device-info` | `device/DeviceInfoCollector.kt` (~45 fields) |
| `POST /player/downloaded-files` | `ContentRepository.reportInventory()` |
| `POST /player/events` | `data/repo/EventReporter.kt` |
| `POST /player/screenshot` | `service/CommandExecutor.kt` + `MainActivity.captureScreenshot()` |

### All 21 commands in `SCREEN_COMMAND_ARRAY`

`SCREENSHOT · SET_VOLUME · SET_BRIGHTNESS · RESTART_APP · REBOOT_DEVICE · CLEAR_CACHE · SYNC_NOW ·
KIOSK_ON · KIOSK_OFF · TIME_SYNC · POWER_ON · POWER_OFF · UPDATE_APP · DELETE_UNUSED_MEDIA ·
GET_DOWNLOAD_STATUS · CLEAR_DOWNLOAD_QUEUE · SYNC_LOCAL_REPORTS · REFETCH_PLAYLIST · APPLY_CONFIG ·
START_REALTIME_EVENT_CAPTURE · STOP_REALTIME_EVENT_CAPTURE`

Each acknowledgement describes **what the device actually managed**, not what it was asked for. See
§6 for which ones degrade and why — `KIOSK_ON` / `KIOSK_OFF` in particular are acked as an honest
failure, because this build deliberately contains no device lockdown (§12).

### Content lifecycle

What a screen does from the moment it is paired:

1. **Paired, nothing assigned.** The server answers `hasContent:false` — a documented, normal state,
   not a fault — and the screen shows **"No Content Assigned"** with a line telling whoever is
   standing there to assign a playlist in the portal. Nothing is played or downloaded.
2. **A playlist is assigned.** The CMS bumps `contentVersion`, the next heartbeat comes back
   `contentStale:true`, and the player syncs. A screen with no content beats every **15s** instead
   of 60 so this feels immediate while an engineer is commissioning it; it drops back to the
   server's interval as soon as content arrives.
3. **Download.** Every asset is fetched to local storage, sequentially, each with **3 attempts and a
   linear backoff**. A file that exhausts its retries does not abort the pass — the rest still
   download and it is retried on the next sync, which usually succeeds because the common cause is
   an S3 signature that expired mid-download and the next manifest re-signs it.
4. **Play.** Only once the download finishes does the new playlist go on screen, and playback reads
   **only from disk** — never from the signed URL. A network drop after this point changes nothing.

All of it runs on the foreground service's IO scope, so the UI never blocks. The one thing that
deliberately does not wait is a playlist that is **already playing**: a content change downloads in
the background while the old loop keeps running, and swaps only when the new one is complete. A
screen in a public place should never go black to fetch its next playlist.

### Download progress and failure

| Situation | What is on screen |
|---|---|
| Nothing playing, nothing to download | "No Content Assigned" + what to do next |
| Nothing playing, downloading | Full panel: spinner, "Downloading 3 of 10 files", per-file progress bar and percentage, current filename |
| Already playing, downloading | Small bottom-left chip with the file count and bar — live content is not covered |
| A file permanently failed | "Some files could not be downloaded — N of M failed", and the playlist plays with what did arrive |

The indicator clears itself the moment the download completes. The download state is also a line in
the diagnostics overlay, so it is readable after the fact.

### Playback

`PlanBuilder` collapses both manifest shapes — the playlist tree (`layouts → zones → slides`) and
the cluster grid (this screen's column of a video wall) — into one `PlaybackPlan`, so there is a
single renderer. `PlaybackEngine.frameAt(plan, now, base)` is a pure function recomputed four times
a second rather than a timer that advances: a decode stall or a slow command cannot accumulate drift.

- Layout advance by `durationSeconds`; zones shorter than their layout loop inside it.
- Zone geometry rendered as fractions of the panel (percentages first, then `ratioConfig`, then
  authored pixels ÷ canvas), so a 1920×1080 playlist survives a portrait or 4K panel.
- Overlapping zones by `zIndex` — a ticker over video is the point, not a bug.
- `fitMode` cover/contain/fill, per-slide and per-zone mute, fade transitions.
- Cluster sync: members count from the shared `loopOriginAt`, so a rebooted panel rejoins where its
  neighbours are instead of restarting at slot 0 and tearing the wall. Videos seek into the clip on
  entry for the same reason. Blank cells still occupy their beat.
- Shuffle is seeded by cycle index, so a shuffled playlist on a wall shuffles *identically* on
  every panel.

### Offline behaviour

This is the part the backend was designed for, and all of it works:

- Every asset is downloaded and **played only from disk**, keyed by `cacheKey` — never by the signed
  URL, which is re-signed on every sync and would otherwise force a full re-download each time.
- The manifest is cached before any download starts, so a box that boots into a dead uplink plays
  yesterday's loop rather than a holding card.
- Proof-of-play and diagnostic events queue in Room with a `clientEventId` minted when the event
  happens. A batch that landed but whose response was lost is deduped server-side on retry by the
  unique `(screen, clientEventId)` index — so the whole local queue can be resent without tracking
  what got through.
- Queues flush the moment a link returns, not at the next heartbeat.
- A failed sync clears nothing. The screen keeps playing what it has.

### Logging and monitoring

- **CMS Logs tab** (`/player/events`): the `playlist_event` render trace (`DOWNLOAD_STARTED`,
  `PLAYING`, `SKIPPED`, `RENDER_FAILED`) and `in_app` lifecycle. Gated exactly as the server gates
  it — when live capture is off the app stops *queueing* as well as sending, which is the point of
  the gate.
- **Telemetry** on every heartbeat: CPU from `/proc/stat` deltas, SoC temperature from the thermal
  nodes, RAM/ROM, network type, IP, MAC.
- **On-device diagnostics overlay**, opened with the INFO / MENU / yellow key: identity, heartbeat
  age, whether the CMS would currently show this screen as offline, clock offset, content version
  and source, queue depths, cache size, and the last 400 log lines. This exists because these boxes
  are mounted high on walls in public stations — the realistic diagnosis is someone reading this
  from the floor, not an adb session over the network that may be the broken thing.

### Schedules

`device/ScheduleEnforcer.kt` implements the multi-row `screenTurnOffSchedules` and
`brightnessSchedules` the backend added — the *"a player change is still needed"* item from
`AMS_CONFIGURATION_TAB.md` §4. It reads the arrays and falls back to the legacy mirrored
`powerOffAt`/`powerOnAt` pair, evaluates everything in the screen's own IANA timezone against the
server-corrected clock, and handles midnight-crossing windows with the day filter applied to the
**start** day. Plus `autoRestartAt`, fired at most once per local day.

---

## 12. Deployment on the Droidlogic boxes

**This build contains no app pinning, lock task, launcher replacement or settings blocking.** An
earlier version called `startLockTask()` whenever the CMS's `kioskMode` setting was on — which it is
by default — and on a box that is not a provisioned device owner that raises Android's *"App is
pinned"* confirmation dialog and leaves it sitting on a public screen with nobody there to dismiss
it. `settings.kioskMode` and `settings.lockDeviceSettings` are now read and deliberately ignored,
and the device-admin component is gone entirely.

Locking a box down is a deployment decision, not something the player should do to itself. If a
production install needs it, the options are an MDM, or device-owner provisioning at install time —
both outside this app.

**Two files could not be deleted through the tooling that wrote this change and are now inert.
Remove them:**

```bash
git rm app/src/main/java/com/example/digi/device/DeviceAdminReceiver.kt
git rm app/src/main/res/xml/device_admin.xml
```

**Boot behaviour.** `BootReceiver` starts the Activity on `BOOT_COMPLETED`, falling back to the
service. Note that Android 15 forbids starting a `mediaPlayback` foreground service from
`BOOT_COMPLETED`; the fleet's boxes are SDK 30, where it works. If a future install needs the box to
boot straight into content with no launcher in between, add the HOME intent-filter back to
`MainActivity` — the exact snippet is in a comment in `AndroidManifest.xml` where it used to be. It
is left out by default because it makes every press of Home on a development box raise a
"choose a launcher" chooser.

## 13. What degrades, and where

A normal Android app cannot do several things a signage operator expects. Every one of these is
reported truthfully in the command acknowledgement rather than acked as a clean success:

| Command | Full capability | Degrades to |
|---|---|---|
| `REBOOT_DEVICE` | `su` — these boxes are rooted | reported failure, *not* a silent app restart |
| `SET_BRIGHTNESS` | `WRITE_SETTINGS` granted | in-app window dim, flagged as partial |
| `KIOSK_ON` / `KIOSK_OFF` | — | **not implemented** — acked as a failure naming MDM or device-owner provisioning as the alternative (§12) |
| `POWER_OFF` | HDMI-CEC standby where the sysfs node is writable | black + muted in-app, loop still running so `POWER_ON` resumes in the right place |
| `UPDATE_APP` | — | reported unimplemented; push the APK through MDM or adb |

`settings.lockDeviceSettings` is ignored for the same reason as `kioskMode`.

## 14. Known gaps and things worth deciding

1. **`GET /player/commands` is at-most-once.** The server marks commands delivered at fetch time,
   before this device has done anything with them. The app persists every fetched batch to disk and
   replays unacknowledged ones after a restart, which closes the common case (app killed
   mid-execution) but cannot close the server-side gap — a command lost in flight is gone. Worth a
   backend change if remote commands ever become load-bearing.
2. **`PlaylistEditorController` never calls `invalidateContent`.** Editing the contents of an
   already-deployed playlist does not bump `contentVersion`, so this player will not learn about it:
   it is doing the right thing with the answer it is given. Still open on the backend side.
3. **Wi-Fi SSID and RSSI report null** from API 27 up. They need a location permission, and a
   signage box has nobody present to grant a runtime permission. The fields are sent as null rather
   than faked.
4. **No in-app update.** `UPDATE_APP` acks as unimplemented.
5. **`keepOnTop` is still honoured.** When that CMS setting is on, the service pulls the player back
   to the foreground if something else takes it. That is a soft bring-to-front, not a lock — Home
   still works and no dialog appears — but say the word and it comes out too.
6. **Nothing plays until the whole playlist has downloaded** on a screen that has no content yet.
   That is what was asked for and it is the right default, but it does mean a first assignment of
   400MB over a slow uplink shows the progress panel for as long as that takes. A screen that is
   already playing is unaffected. The one exception is a restart part-way through a download: on the
   next start the cached manifest is restored and whatever is on disk plays immediately, because a
   partial loop beats a black screen while the rest comes down.
7. **Not yet run against a live backend.** The AES round-trip, the playback scheduler and the
   schedule rules have unit tests; everything that crosses the wire has been written against
   `PlayerController.js` and the Joi schemas but not exercised end-to-end. Pair one screen against
   the dev server before rolling anything out.

---

## 15. Layout

```
core/         DigiApp (hand-wired object graph), AmsConstants (mirror of Utils/Constants.js),
              AppLog (ring buffer), ServerClock (offset + ISO parsing), NetworkMonitor, PlayerHost
data/crypto/  SessionCipher (v2), AesCipher (legacy), CryptoInterceptor
data/remote/  PlayerApi (all 11 routes), ApiClient, ApiResult, AuthInterceptor, dto/
data/local/   Room (4 queues), PlayerStore (token, manifest, settings)
data/repo/    Pairing, Content, Heartbeat, Command, EventReporter, ProofOfPlayRecorder
media/        MediaCache — download-once, play-from-disk, cacheKey-addressed
player/       PlaybackPlan, PlanBuilder, PlaybackEngine (pure, tested)
device/       DeviceInfoCollector, TelemetryCollector, DeviceController, ScheduleEnforcer
service/      PlayerService (the loop), CommandExecutor, BootReceiver, RestartReceiver
ui/           MainActivity, pairing/, player/, diagnostics/, theme/
```
