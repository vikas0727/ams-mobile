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

@Serializable
data class DeviceInfoResponse(
    val propertyCount: Int? = null,
    val reportedAt: String? = null,
)
