package com.example.digi.service

import android.content.Context
import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.PlayerHost
import com.example.digi.data.local.PlayerStore
import com.example.digi.data.remote.ApiClient
import com.example.digi.data.remote.ApiResult
import com.example.digi.data.remote.PlayerApi
import com.example.digi.data.remote.apiCall
import com.example.digi.data.remote.dto.SettingsDto
import com.example.digi.data.repo.CommandRepository
import com.example.digi.data.repo.ContentRepository
import com.example.digi.data.repo.EventReporter
import com.example.digi.data.repo.ProofOfPlayRecorder
import com.example.digi.device.DeviceController
import com.example.digi.device.SettingsApplier
import com.example.digi.media.MediaCache
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Executes every command in `SCREEN_COMMAND_ARRAY` and reports honestly what happened.
 *
 * The guiding rule is that an ack must describe the world, not the intent. Android does not let an
 * ordinary app reboot a box or set screen brightness, so several of these degrade — and when they
 * do, the ack says so in `errorMessage` or in the result payload rather than reporting a clean
 * success. An operator who sees "Volume set to 47%" and finds the panel silent has no way to work
 * out why; one who sees "WRITE_SETTINGS not granted" does.
 *
 * Commands that do not exist in this build are acked as failures with a clear reason rather than
 * ignored, so they leave the CMS queue instead of being re-delivered forever.
 */
class CommandExecutor(
    private val context: Context,
    private val api: PlayerApi,
    private val store: PlayerStore,
    private val commands: CommandRepository,
    private val content: ContentRepository,
    private val cache: MediaCache,
    private val events: EventReporter,
    private val proofOfPlay: ProofOfPlayRecorder,
    private val settingsApplier: SettingsApplier,
) {

    /** Set when a command asks for a restart; the service performs it after the ack has gone out. */
    var pendingRestart: Boolean = false
        private set

    suspend fun execute(pending: CommandRepository.Pending) {
        AppLog.i(TAG, "Executing ${pending.command} (${pending.id})")
        events.appEvent(AmsConstants.LogAction.COMMAND_RECEIVED, status = pending.command)

        val outcome = try {
            run(pending)
        } catch (e: Throwable) {
            AppLog.e(TAG, "Command ${pending.command} threw", e)
            DeviceController.Outcome.failed(e.message ?: e::class.java.simpleName)
        }

        // A degraded success is still a success — the operator's instruction had an effect — but
        // the detail rides along so the command history explains itself.
        commands.acknowledge(
            id = pending.id,
            success = outcome.success,
            detail = outcome.detail,
            result = buildJsonObject {
                put("detail", outcome.detail)
                put("degraded", outcome.degraded)
            },
        )

        events.appEvent(
            action = if (outcome.success) AmsConstants.LogAction.COMMAND_COMPLETED
            else AmsConstants.LogAction.COMMAND_FAILED,
            status = pending.command,
        )
    }

    private suspend fun run(pending: CommandRepository.Pending): DeviceController.Outcome {
        val host = PlayerHost.current()

        return when (pending.command) {

            AmsConstants.Command.SCREENSHOT -> captureAndUpload(pending.id)

            AmsConstants.Command.SET_VOLUME -> {
                val level = pending.payload.level()
                    ?: return DeviceController.Outcome.failed("SET_VOLUME had no level in its payload")
                DeviceController.setVolume(context, level)
            }

            AmsConstants.Command.SET_BRIGHTNESS -> {
                val level = pending.payload.level()
                    ?: return DeviceController.Outcome.failed("SET_BRIGHTNESS had no level in its payload")
                val system = DeviceController.setSystemBrightness(context, level)
                // Apply the in-app dim as well as the system value: on a box without WRITE_SETTINGS
                // it is the only thing that will actually change, and on one with it the two agree.
                host?.applyWindowBrightness(level)
                system
            }

            AmsConstants.Command.RESTART_APP -> {
                pendingRestart = true
                DeviceController.Outcome.ok("App will restart once this acknowledgement is sent")
            }

            AmsConstants.Command.REBOOT_DEVICE -> {
                // Flush first. A reboot loses whatever is only in memory, and unsent playback
                // evidence is the one thing here that has money attached to it.
                proofOfPlay.flush()
                events.flush()
                DeviceController.rebootDevice(context)
            }

            AmsConstants.Command.CLEAR_CACHE -> {
                val removed = content.clearCache()
                // Force a re-sync: every slide has just lost its file, and waiting for the CMS to
                // change something would leave the screen blank until it did.
                content.invalidate()
                DeviceController.Outcome.ok("Cleared $removed cached file(s); re-sync queued")
            }

            AmsConstants.Command.SYNC_NOW, AmsConstants.Command.REFETCH_PLAYLIST -> {
                content.invalidate()
                val ok = content.sync()
                if (ok) DeviceController.Outcome.ok("Content re-synced")
                else DeviceController.Outcome.failed("Re-sync failed — the server was unreachable")
            }

            // Not implemented, on purpose. Android's only in-app lockdown is lock task mode, and
            // on a box that is not a provisioned device owner it raises the "App is pinned"
            // confirmation dialog — which then sits on a public screen waiting for a person who is
            // not there. Acked as a failure with the reason rather than silently ignored, so the
            // command leaves the CMS queue and the operator can see why.
            AmsConstants.Command.KIOSK_ON, AmsConstants.Command.KIOSK_OFF ->
                DeviceController.Outcome.failed(
                    "Kiosk lockdown is not implemented in this build — provision the box as device " +
                        "owner or use an MDM if the screen needs locking down"
                )

            AmsConstants.Command.TIME_SYNC -> {
                // The offset is already maintained from every response's serverTime; this command
                // just forces an immediate observation and reports the correction.
                val before = com.example.digi.core.ServerClock.currentOffsetMs
                when (val result = apiCall { api.me() }) {
                    is ApiResult.Success -> {
                        com.example.digi.core.ServerClock.observe(result.data.serverTime)
                        val after = com.example.digi.core.ServerClock.currentOffsetMs
                        DeviceController.Outcome.ok(
                            "Clock offset ${after}ms (was ${before}ms); the device clock itself is unchanged"
                        )
                    }
                    else -> DeviceController.Outcome.failed("Could not reach the server to read its time")
                }
            }

            AmsConstants.Command.POWER_ON -> {
                host?.setBlanked(false)
                DeviceController.setDisplayPower(context, on = true)
            }

            AmsConstants.Command.POWER_OFF -> {
                host?.setBlanked(true)
                DeviceController.setDisplayPower(context, on = false)
            }

            AmsConstants.Command.UPDATE_APP -> DeviceController.Outcome.failed(
                "In-app update is not implemented in this build — push the APK through MDM or adb"
            )

            AmsConstants.Command.DELETE_UNUSED_MEDIA -> {
                val removed = content.deleteUnusedMedia()
                DeviceController.Outcome.ok("Deleted $removed unused file(s)")
            }

            AmsConstants.Command.GET_DOWNLOAD_STATUS -> {
                val all = cache.inventory()
                val done = all.count { it.status == AmsConstants.DownloadStatus.DOWNLOADED }
                val failed = all.count { it.status == AmsConstants.DownloadStatus.FAILED }
                content.reportInventory()
                DeviceController.Outcome.ok(
                    "$done of ${all.size} file(s) downloaded, $failed failed, " +
                        "${cache.occupiedBytes() / (1024 * 1024)}MB used, " +
                        "${cache.freeSpaceBytes() / (1024 * 1024)}MB free"
                )
            }

            AmsConstants.Command.CLEAR_DOWNLOAD_QUEUE -> {
                // Only the in-flight queue, not the cache: an operator clearing a stuck download
                // does not expect the content currently on the wall to vanish with it.
                val removed = cache.evictOrphans(graceMillis = 0)
                DeviceController.Outcome.ok("Download queue cleared ($removed incomplete file(s) removed)")
            }

            AmsConstants.Command.SYNC_LOCAL_REPORTS -> {
                val plays = proofOfPlay.flush()
                val logs = events.flush()
                content.reportInventory()
                DeviceController.Outcome.ok("Uploaded $plays play record(s) and $logs log event(s)")
            }

            AmsConstants.Command.APPLY_CONFIG -> {
                /*
                 * This used to return "settings will be applied on the next tick" and apply
                 * nothing — the settings arrived, were stored, and were then acted on only for
                 * volume and brightness. Worse, it acked SUCCESS, so the CMS showed a clean command
                 * history for a change the screen had not made. A setting that visibly fails gets
                 * reported; one that silently succeeds costs somebody a week of doubting their eyes.
                 *
                 * It now runs the real pass, forced — an operator who pressed Save is entitled to
                 * have the device re-assert the value even where it believes it already matches —
                 * and reports back exactly what it managed and what this hardware cannot do.
                 */
                val settings = pending.payload.settings() ?: store.loadSettings()
                if (settings == null) {
                    DeviceController.Outcome.failed("No settings to apply — none in the payload and none cached")
                } else {
                    // Cache the payload's copy so a later cold start applies it too, rather than
                    // reverting to whatever the last heartbeat happened to carry.
                    store.saveSettings(settings)
                    val result = settingsApplier.apply(settings, force = true)
                    if (result.skipped.isEmpty()) DeviceController.Outcome.ok(result.summary)
                    // Degraded, not failed: the supported settings really were applied, and calling
                    // the whole command a failure would hide that.
                    else DeviceController.Outcome.partial(result.summary)
                }
            }

            AmsConstants.Command.START_REALTIME_CAPTURE -> {
                store.realtimeCaptureEnabled = true
                events.appEvent(AmsConstants.LogAction.APP_STARTED, status = "capture-on")
                DeviceController.Outcome.ok("Live event capture enabled")
            }

            AmsConstants.Command.STOP_REALTIME_CAPTURE -> {
                // Flush what is already queued before closing the gate, so the last few seconds an
                // operator was actually watching for are not thrown away by their own stop.
                events.flush()
                store.realtimeCaptureEnabled = false
                DeviceController.Outcome.ok("Live event capture disabled")
            }

            else -> DeviceController.Outcome.failed(
                "Unknown command '${pending.command}' — this player build does not implement it"
            )
        }
    }

    /**
     * SCREENSHOT: capture the window and upload it as multipart.
     *
     * `commandId` goes up as a form field so the server closes out this very command; the ack that
     * follows is then redundant but harmless, and sending it keeps one code path for every command.
     */
    private suspend fun captureAndUpload(commandId: String): DeviceController.Outcome {
        val host = PlayerHost.current()
            ?: return DeviceController.Outcome.failed("Player UI is not in the foreground to capture")

        val file: File = host.captureScreenshot()
            ?: return DeviceController.Outcome.failed("Screen capture failed on this device")

        return try {
            val part = MultipartBody.Part.createFormData(
                "file",
                file.name,
                file.asRequestBody("image/png".toMediaType()),
            )
            val result = apiCall {
                api.screenshot(part, commandId.toRequestBody("text/plain".toMediaType()))
            }
            when (result) {
                is ApiResult.Success -> DeviceController.Outcome.ok("Screenshot uploaded (${file.length() / 1024}KB)")
                else -> DeviceController.Outcome.failed("Screenshot upload failed: $result")
            }
        } finally {
            // Always delete: a fleet that accumulates one PNG per screenshot request fills its own
            // disk over a year of diagnostics.
            runCatching { file.delete() }
        }
    }

    fun consumeRestart(): Boolean {
        val value = pendingRestart
        pendingRestart = false
        return value
    }

    /**
     * `{settings: {...}}` as APPLY_CONFIG carries it.
     *
     * Returns null rather than throwing on a payload this build cannot parse — a newer CMS may send
     * a field this app has never heard of, and refusing the whole command over one unknown key
     * would strand every setting in it. kotlinx is configured to ignore unknown keys for exactly
     * this reason; this catch is for a payload that is not a settings object at all.
     */
    private fun JsonObject?.settings(): SettingsDto? {
        val element: JsonElement = this?.get("settings") ?: return null
        return runCatching {
            ApiClient.json.decodeFromJsonElement(SettingsDto.serializer(), element)
        }.getOrElse {
            AppLog.w(TAG, "APPLY_CONFIG payload could not be read; falling back to the cached settings", it)
            null
        }
    }

    /** `{level: 0-100}` for both volume and brightness. */
    private fun JsonObject?.level(): Int? {
        val element: JsonElement = this?.get("level") ?: return null
        return runCatching { element.jsonPrimitive.int }.getOrNull()
    }

    private companion object {
        const val TAG = "CommandExec"
    }
}
