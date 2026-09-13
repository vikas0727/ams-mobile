package com.example.digi.data.repo

import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.ServerClock
import com.example.digi.data.local.PlayerStore
import com.example.digi.data.remote.ApiResult
import com.example.digi.data.remote.PlayerApi
import com.example.digi.data.remote.apiCall
import com.example.digi.data.remote.dto.HeartbeatRequest
import com.example.digi.data.remote.dto.SettingsDto
import com.example.digi.device.TelemetryCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The heartbeat: one request a minute that carries telemetry up and three answers down.
 *
 * It is the backbone of the whole device loop. Between syncs the player asks only two questions —
 * "is my content stale?" and "is anything queued for me?" — and both are answered here, which is
 * what keeps the expensive manifest call off the critical path of every beat.
 *
 * One backend quirk is worth encoding rather than rediscovering: `currentlyPlaying` is folded into
 * the telemetry object server-side and is **dropped entirely when `telemetry` is absent**. So a
 * telemetry object is always sent, even when every probe inside it came back null.
 */
class HeartbeatRepository(
    private val api: PlayerApi,
    private val store: PlayerStore,
    private val telemetry: TelemetryCollector,
) {

    data class Beat(
        val contentStale: Boolean,
        val pendingCommands: Int,
        val settings: SettingsDto?,
        /** True only on the beat where the Logs tab was switched on, so the caller can emit a
         *  marker event and flush at once — an empty Logs tab is otherwise indistinguishable from
         *  a broken one. */
        val captureJustEnabled: Boolean = false,
    )

    private val _online = MutableStateFlow(false)

    /** Whether the CMS is actually reachable — a successful beat, not merely a link being up. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * @param currentlyPlaying what is on the wall right now, shown verbatim in the CMS screen list
     * @return the server's answer, or null when the beat did not land
     */
    suspend fun beat(currentlyPlaying: String?): Beat? {
        val request = HeartbeatRequest(
            contentVersion = store.contentVersion,
            currentlyPlaying = currentlyPlaying,
            telemetry = telemetry.collect(),
        )

        return when (val result = apiCall { api.heartbeat(request) }) {
            is ApiResult.Success -> {
                val body = result.data
                ServerClock.observe(body.serverTime)
                store.lastHeartbeatOkAt = ServerClock.now()
                store.saveSettings(body.settings)
                body.heartbeatIntervalSeconds?.let { store.heartbeatIntervalSeconds = it }
                val captureJustEnabled = applyCaptureFlag(body.realtimeCaptureEnabled)
                _online.value = true
                _lastError.value = null

                if (body.contentStale) {
                    AppLog.i(
                        TAG,
                        "Server has v${body.contentVersion}, this screen holds v${store.contentVersion} — re-syncing"
                    )
                }
                Beat(body.contentStale, body.pendingCommands, body.settings, captureJustEnabled)
            }

            is ApiResult.Unauthorized -> {
                // The screen was unpaired, suspended or deleted in the CMS. The token is
                // cryptographically valid for a year but the database check behind every request
                // has stopped honouring it, so there is nothing to retry.
                AppLog.w(TAG, "Heartbeat rejected — this screen is no longer paired: ${result.message}")
                _online.value = false
                _lastError.value = result.message
                store.unpair()
                null
            }

            else -> {
                _online.value = false
                _lastError.value = result.toString()
                AppLog.d(TAG, "Heartbeat failed: $result")
                null
            }
        }
    }

    /**
     * Keep local log capture in step with what the CMS thinks.
     *
     * Null means an older backend that does not send the field — leave whatever the commands set,
     * rather than silently turning capture off on a fleet whose server has not been updated yet.
     */
    private fun applyCaptureFlag(enabled: Boolean?): Boolean {
        if (enabled == null) return false
        if (enabled == store.realtimeCaptureEnabled) return false
        store.realtimeCaptureEnabled = enabled
        AppLog.i(TAG, if (enabled) "Live event capture ON (per heartbeat)" else "Live event capture OFF (per heartbeat)")
        return enabled
    }

    /** Seconds between beats, as the server most recently specified (SCREEN_OFFLINE_AFTER_SECONDS / 3). */
    fun intervalSeconds(): Int = store.heartbeatIntervalSeconds

    /** How long since the CMS last heard from this device — what the diagnostics overlay shows. */
    fun secondsSinceLastSuccess(): Long {
        val last = store.lastHeartbeatOkAt
        if (last <= 0) return -1
        return (ServerClock.now() - last) / 1000
    }

    /** True once the CMS would already have marked this screen offline (180s without a beat). */
    fun consideredOfflineByServer(): Boolean {
        val since = secondsSinceLastSuccess()
        return since < 0 || since > AmsConstants.DEFAULT_HEARTBEAT_SECONDS * 3
    }

    private companion object {
        const val TAG = "Heartbeat"
    }
}
