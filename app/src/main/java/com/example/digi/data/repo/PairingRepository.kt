package com.example.digi.data.repo

import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.ServerClock
import com.example.digi.data.local.PlayerStore
import com.example.digi.data.remote.ApiResult
import com.example.digi.data.remote.PlayerApi
import com.example.digi.data.remote.apiCall
import com.example.digi.data.remote.dto.DeviceInfoRequest
import com.example.digi.data.remote.dto.PairRequest
import com.example.digi.data.remote.dto.ResolutionDto
import com.example.digi.device.DeviceInfoCollector
import java.util.UUID

/**
 * Pairing: the one-time exchange of a 6-character code for a long-lived player token.
 *
 * The device identity deserves a note. `deviceUniqueId` is generated once, on first launch, and
 * kept forever — including across an unpair. It is baked into the token, and `VerifyPlayerToken`
 * compares the id inside the token with the one stored on the screen on **every single request**.
 * A device that regenerated its id on each pair would authenticate fine and then be rejected as
 * "paired to a different device" the moment anything else re-paired it. ANDROID_ID is used as the
 * seed where available so a factory-reset box that is re-paired looks like the same hardware to the
 * CMS, which is what an operator expects.
 */
class PairingRepository(
    private val api: PlayerApi,
    private val store: PlayerStore,
    private val deviceInfo: DeviceInfoCollector,
    private val screenWidth: () -> Int,
    private val screenHeight: () -> Int,
) {

    sealed interface PairOutcome {
        data class Paired(val screenName: String?) : PairOutcome
        /** The code was wrong, expired, or the screen is already on other hardware. Actionable text. */
        data class Rejected(val message: String) : PairOutcome
        data class Offline(val message: String) : PairOutcome
    }

    fun deviceId(): String {
        store.deviceUniqueId?.let { return it }
        val generated = "android-" + UUID.randomUUID().toString().replace("-", "").take(16)
        store.deviceUniqueId = generated
        AppLog.i(TAG, "Generated device id $generated")
        return generated
    }

    suspend fun pair(code: String): PairOutcome {
        val trimmed = code.trim().uppercase()
        if (trimmed.length < 4) return PairOutcome.Rejected("Enter the full pairing code")

        val id = deviceId()
        val request = PairRequest(
            pairingCode = trimmed,
            deviceUniqueId = id,
            platform = AmsConstants.PLATFORM_ANDROID,
            appVersion = com.example.digi.BuildConfig.VERSION_NAME,
            osVersion = "Android ${android.os.Build.VERSION.RELEASE}",
            resolution = ResolutionDto(screenWidth(), screenHeight()),
            // Inline rather than as a follow-up call: it saves a round trip on first boot and means
            // the CMS Additional Info tab is populated the instant the operator finishes pairing.
            deviceInfo = deviceInfo.collect(id),
        )

        return when (val result = apiCall { api.pair(request) }) {
            is ApiResult.Success -> {
                val body = result.data
                store.playerToken = body.playerToken
                store.screenId = body.screen.id
                store.screenName = body.screen.name
                store.orientation = body.screen.orientation
                store.saveSettings(body.screen.settings)
                body.heartbeatIntervalSeconds?.let { store.heartbeatIntervalSeconds = it }
                // A freshly paired screen holds nothing, so -1 guarantees the first heartbeat
                // reports stale content and pulls a manifest immediately rather than waiting for
                // the CMS to change something.
                store.contentVersion = -1
                AppLog.i(TAG, "Paired as '${body.screen.name}' (${body.screen.id})")
                PairOutcome.Paired(body.screen.name)
            }

            is ApiResult.Offline -> PairOutcome.Offline(
                "Cannot reach the AMS server. Check the network and the server address."
            )

            is ApiResult.Unauthorized -> PairOutcome.Rejected(result.message ?: "Pairing rejected")

            is ApiResult.Failed -> PairOutcome.Rejected(
                result.message ?: "Pairing failed (HTTP ${result.code})"
            )
        }
    }

    /**
     * Confirm the token still works and refresh settings — run at app start, before the first sync.
     *
     * @return false when the screen has been unpaired, suspended or deleted in the CMS, in which
     *         case the local credential has already been discarded.
     */
    suspend fun verify(): Boolean {
        if (store.playerToken.isNullOrBlank()) return false

        return when (val result = apiCall { api.me() }) {
            is ApiResult.Success -> {
                val me = result.data
                ServerClock.observe(me.serverTime)
                store.screenName = me.name
                store.orientation = me.orientation
                store.saveSettings(me.settings)
                me.heartbeatIntervalSeconds?.let { store.heartbeatIntervalSeconds = it }
                true
            }

            is ApiResult.Unauthorized -> {
                AppLog.w(TAG, "Token rejected — unpairing locally: ${result.message}")
                store.unpair()
                false
            }

            // Offline or a server error says nothing about whether this screen is still paired.
            // Treating either as an unpair would strand a whole fleet on the pairing screen during
            // a backend deployment.
            else -> true
        }
    }

    /** Push a fresh property dump — after an app update, or when the network details change. */
    suspend fun reportDeviceInfo(): Boolean {
        val result = apiCall { api.deviceInfo(DeviceInfoRequest(deviceInfo.collect(deviceId()))) }
        if (result is ApiResult.Success) {
            AppLog.i(TAG, "Device info reported (${result.data.propertyCount} properties)")
            return true
        }
        AppLog.d(TAG, "Device info report deferred: $result")
        return false
    }

    fun unpair() {
        AppLog.i(TAG, "Unpairing this device locally")
        store.unpair()
    }

    private companion object {
        const val TAG = "Pairing"
    }
}
