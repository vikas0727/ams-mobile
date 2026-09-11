# AMS Player (ams-mobile)

The Android TV signage player for the AMS backend at `D:\Projects\AMS`. It pairs to a screen with a
6-character code, pulls the content manifest, plays it fullscreen from local disk, heartbeats
telemetry back, obeys remote commands, and uploads playback evidence — continuing to do all of it
through an outage except the parts that need the network.

Package `com.example.digi`, minSdk 24, targetSdk 36, Kotlin 2.0.21, Compose, Media3.

---

## 1. Configure before the first build

Create `local.properties` in the project root (git-ignored) with four values:

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk

digi.apiBaseUrl=http://10.0.2.2:5000/api/v1/
digi.aesSecretKey=<the backend's AES_SECRET_KEY, verbatim>
digi.aesIv=<the backend's AES_IV, 32 hex chars>
digi.aesEnabled=true
```

Both AES values are in the backend's `.env.dev` / `.env.prod`. They must match exactly — the app
runs the secret through SHA-256 the same way `Utils/cryptoUtil.js` does, and the IV is hex-decoded,
not hashed. A build left on the placeholder key will fail to decrypt every response on the first
call, loudly.

`digi.apiBaseUrl` needs the trailing slash and the `/api/v1` prefix. `10.0.2.2` is the emulator's
alias for the host machine; on a real box use the server's LAN address (`http://192.168.1.x:5000/api/v1/`).

---

## 2. Why the app does AES at all

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

## 3. What is implemented

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
§6 for which ones degrade and why.

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

## 4. Running it

```bash
./gradlew assembleDebug
./gradlew test            # AES vector, playback scheduler, schedule rules
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then: generate a pairing code in the CMS for a screen, enter it on the device (on-screen character
grid, D-pad navigable — no keyboard needed), and it pairs and starts playing.

The standalone Postman collection at `D:\Projects\AMS\postman\AMS_Player_Mobile_API.postman_collection.json`
mints a code without opening the full CMS collection.

---

## 5. Deployment on the Droidlogic boxes

Provision as device owner once per box, straight after a factory reset and before any account is
added:

```bash
adb shell dpm set-device-owner com.example.digi/.device.DeviceAdminReceiver
```

This is what makes `KIOSK_ON`, `REBOOT_DEVICE` and the settings lock genuinely work rather than
degrade, and it lets the app be set as the home launcher (it declares the HOME intent-filter), so
the box boots straight into content and Home cannot escape it.

**Boot behaviour, stated plainly:** Android 15 forbids starting a `mediaPlayback` foreground service
from `BOOT_COMPLETED`. The fleet's boxes are SDK 30 where it works, but the version-proof answer is
the launcher deployment above — `BootReceiver` starts the Activity first and only falls back to the
service.

---

## 6. What degrades, and where

A normal Android app cannot do several things a signage operator expects. Every one of these is
reported truthfully in the command acknowledgement rather than acked as a clean success:

| Command | Full capability | Degrades to |
|---|---|---|
| `REBOOT_DEVICE` | device owner, or `su` (these boxes are rooted) | reported failure — *not* a silent app restart |
| `SET_BRIGHTNESS` | `WRITE_SETTINGS` granted | in-app window dim, flagged as partial |
| `KIOSK_ON` | device owner → real lock task | Android's pinning confirmation, which nobody is there to accept |
| `POWER_OFF` | HDMI-CEC standby where the sysfs node is writable | black + muted in-app, loop still running so `POWER_ON` resumes in the right place |
| `UPDATE_APP` | — | reported unimplemented; push the APK through MDM or adb |
| `lockDeviceSettings` | device owner | reported as unavailable |

---

## 7. Known gaps and things worth deciding

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
5. **Not yet run against a live backend.** The AES round-trip, the playback scheduler and the
   schedule rules have unit tests; everything that crosses the wire has been written against
   `PlayerController.js` and the Joi schemas but not exercised end-to-end. Pair one screen against
   the dev server before rolling anything out.

---

## 8. Layout

```
core/         DigiApp (hand-wired object graph), AmsConstants (mirror of Utils/Constants.js),
              AppLog (ring buffer), ServerClock (offset + ISO parsing), NetworkMonitor, PlayerHost
data/crypto/  AesCipher, CryptoInterceptor
data/remote/  PlayerApi (all 11 routes), ApiClient, ApiResult, AuthInterceptor, dto/
data/local/   Room (4 queues), PlayerStore (token, manifest, settings)
data/repo/    Pairing, Content, Heartbeat, Command, EventReporter, ProofOfPlayRecorder
media/        MediaCache — download-once, play-from-disk, cacheKey-addressed
player/       PlaybackPlan, PlanBuilder, PlaybackEngine (pure, tested)
device/       DeviceInfoCollector, TelemetryCollector, DeviceController, ScheduleEnforcer
service/      PlayerService (the loop), CommandExecutor, BootReceiver, RestartReceiver
ui/           MainActivity, pairing/, player/, diagnostics/, theme/
```
