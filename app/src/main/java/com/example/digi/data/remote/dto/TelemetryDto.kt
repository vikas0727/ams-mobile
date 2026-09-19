package com.example.digi.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `POST /player/heartbeat` — called every `heartbeatIntervalSeconds` (60s by default).
 *
 * Deliberately cheap on both sides. Sending `contentVersion` is what makes the manifest call stay
 * off the critical path: the server answers `contentStale` and the player syncs only then.
 *
 * One backend quirk worth knowing: `currentlyPlaying` is folded into the telemetry object
 * server-side and is **dropped entirely when `telemetry` is absent**. So this player always sends a
 * telemetry object, even a sparse one.
 */
@Serializable
data class HeartbeatRequest(
    val contentVersion: Int? = null,
    val currentlyPlaying: String? = null,
    val telemetry: TelemetryDto? = null,
    /** What this device actually has in force — see [AppliedSettingsDto]. */
    val appliedSettings: AppliedSettingsDto? = null,
)

/**
 * The settings this device is actually running, as opposed to the ones the CMS asked for.
 *
 * Without this the portal can only ever show its own intentions. It stores what an operator typed,
 * marks the command acked, and displays the typed value back to them — which is exactly the loop
 * that let a player accept settings and apply none of them without anybody noticing.
 *
 * So the values here are READ BACK where reading back is possible rather than echoed. `volume` in
 * particular comes from the audio manager, not from what was last written to it, because a TV
 * box's volume steps are coarse — asking for 45% on a device with sixteen steps gets you 43.75% —
 * and a site engineer with a remote can move it afterwards. An operator looking at 45 in the portal
 * and 44 on the device is looking at the truth; an operator looking at 45 in both when the panel is
 * at 80 is being misled.
 *
 * [unsupported] is the other half. A device that cannot honour a setting says so here, so the
 * portal can show a switch as ineffective on this hardware rather than as on.
 */
@Serializable
data class AppliedSettingsDto(
    val volume: Int? = null,
    val brightness: Int? = null,
    val appRotation: Int? = null,
    val panelRotation: Int? = null,
    /** Human-readable reasons, e.g. "kiosk mode (needs device-owner provisioning)". */
    val unsupported: List<String> = emptyList(),
    /** ISO-8601 UTC — when this device last ran a settings pass. */
    val appliedAt: String? = null,
)

@Serializable
data class TelemetryDto(
    val appVersion: String? = null,
    val osVersion: String? = null,
    val storageTotalMb: Long? = null,
    val storageFreeMb: Long? = null,
    val ramTotalMb: Long? = null,
    val ramFreeMb: Long? = null,
    val cpuUsagePercent: Double? = null,
    val temperatureCelsius: Double? = null,
    val batteryPercent: Int? = null,
    val networkType: String? = null,
    val ipAddress: String? = null,
    val macAddress: String? = null,
)

@Serializable
data class HeartbeatResponse(
    val serverTime: String? = null,
    val contentVersion: Int? = null,
    /** True when what the player holds differs from what the server has. The only re-sync trigger
     *  in the steady state. */
    val contentStale: Boolean = false,
    val pendingCommands: Int = 0,
    val settings: SettingsDto? = null,
    val heartbeatIntervalSeconds: Int? = null,
    /**
     * Whether an operator has the Logs tab watching this screen.
     *
     * Also delivered as the START/STOP_REALTIME_EVENT_CAPTURE commands, but those are at-most-once
     * — the server marks a command delivered before this device has acted on it. Carrying the flag
     * on every beat makes capture self-correcting instead of depending on one command arriving.
     * Null from an older backend, which is why the player treats null as "no opinion" rather than
     * as false.
     */
    val realtimeCaptureEnabled: Boolean? = null,
)

/* ------------------------------------------------------------------ *
 * Buffered heartbeats — POST /player/heartbeat-backfill
 * ------------------------------------------------------------------ */

/**
 * Heartbeats this device produced while it could not reach the server.
 *
 * A panel whose uplink drops keeps playing from local storage and keeps beating once a minute. Those
 * beats are queued in `pending_heartbeat` and replayed here when the link returns, so the CMS uptime
 * grid — which counts the minutes that carried a beat — shows the screen as having been up rather
 * than as a block of red.
 *
 * The server records these as *backfilled* minutes, kept apart from live ones. That distinction
 * matters and is worth not arguing with: a replayed beat is simultaneously evidence the screen was
 * up and evidence the network was down, and folding the two together would erase the outage from
 * the report whose job is to show outages.
 *
 * ISO-8601 UTC instants. At most 2000 per call; the flusher chunks.
 */
@Serializable
data class HeartbeatBackfillRequest(val beats: List<String>)

@Serializable
data class HeartbeatBackfillResponse(
    val received: Int? = null,
    /** Distinct minutes actually written. Lower than [received] when the device buffered more than
     *  one beat inside the same minute, which is normal — the server counts minutes, not beats. */
    val recorded: Int? = null,
    /** Dropped as too old or in the future. Non-zero usually means this box's clock was wrong while
     *  it was offline, which is worth a log line rather than a retry. */
    val rejected: Int? = null,
    val serverTime: String? = null,
)

/**
 * `POST /player/device-info` — the ~45-field property dump behind the CMS Additional Info tab.
 *
 * Schemaless on both sides, hence [JsonObject]: the key set differs per platform and per player
 * build, and pinning it down would mean a backend migration every time the app reports one more
 * field. Merged rather than replaced server-side, so a partial report can never blank out fields an
 * earlier fuller one established. Sent on pair and when something changes — never per heartbeat.
 */
@Serializable
data class DeviceInfoRequest(val deviceInfo: JsonObject)

/**
 * The playback position, for the HTTP fallback when the socket is not connected.
 *
 * Mirrors the `player-state` socket payload field for field, minus `screenId` — the server takes
 * that from the player token, which makes this route harder to spoof than the socket event.
 */
@Serializable
data class PlaybackStateRequest(
    val mediaId: String? = null,
    val name: String? = null,
    val mediaType: String? = null,
    val positionMs: Long = 0L,
    val slideIndex: Int = -1,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val reportedAt: String? = null,
)

@Serializable
data class PlaybackStateResponse(
    val recordedAt: String? = null,
)

@Serializable
data class DeviceInfoResponse(
    val propertyCount: Int? = null,
    val reportedAt: String? = null,
)
