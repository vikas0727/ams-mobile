package com.example.digi.data.repo

import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.FirebaseTelemetry
import com.example.digi.BuildConfig
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

    /**
     * The one-line "why" appended to the offline message.
     *
     * Deliberately the exception's class name and nothing else - a stack trace on a signage screen
     * helps nobody, but "UnknownHostException" tells an installer to check DNS and
     * "SSLHandshakeException" tells them to check the certificate or the tablet's date, which is
     * the difference between a five-minute fix and a phone call.
     */
    /**
     * The cause in words the person at the box can act on. The server address itself is never
     * shown - it stays in the log (`adb logcat -s Pairing`).
     */
    private fun reasonHint(cause: Throwable?): String {
        if (cause == null) return ""
        val chain = generateSequence(cause) { it.cause }.take(6).toList()
        val text = chain.joinToString(" ") { it.message.orEmpty() }
        fun has(type: Class<*>) = chain.any { type.isInstance(it) }
        val reason = when {
            has(java.net.UnknownHostException::class.java) ->
                "Server name not found - check the box has internet and DNS."
            has(java.net.SocketTimeoutException::class.java) ->
                "The server did not answer in time - check the network or firewall."
            has(java.net.ConnectException::class.java) ->
                "The server refused the connection - is the API running and the port open?"
            text.contains("CLEARTEXT", ignoreCase = true) ->
                "This build only allows https - use a debug build for an http server."
            has(javax.net.ssl.SSLException::class.java) ||
                has(java.security.cert.CertificateException::class.java) ->
                "Secure connection failed - check the server's certificate and the box's date and time."
            text.contains("could not prove its identity") ->
                "Server key does not match this app - this APK was built for a different environment."
            text.contains("Encryption handshake failed") ->
                "The server rejected the secure session (${text.substringAfter("(").substringBefore(")")})."
            has(java.net.NoRouteToHostException::class.java) ->
                "No route to the server - check the box's network connection."
            else -> {
                // Messages like "Failed to connect to <host>" would put the address on screen.
                val host = runCatching { java.net.URI(BuildConfig.API_BASE_URL).host }.getOrNull().orEmpty()
                cause.message?.takeIf { it.isNotBlank() && (host.isEmpty() || !it.contains(host)) }
                    ?: cause::class.java.simpleName
            }
        }
        return "\n$reason"
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
                // The app started unpaired, so telemetry is currently tagged "unpaired". Re-tag now
                // rather than at the next launch: a box that crashes during its first sync is one of
                // the more interesting crashes there is, and it would otherwise arrive anonymous.
                FirebaseTelemetry.identify(
                    screenId = body.screen.id,
                    screenName = body.screen.name,
                    deviceId = store.deviceUniqueId,
                )
                FirebaseTelemetry.event("screen_paired")
                AppLog.i(TAG, "Paired as '${body.screen.name}' (${body.screen.id})")
                PairOutcome.Paired(body.screen.name)
            }

            // The server never answered: DNS, TCP, TLS or a timeout - NOT a rejected code. The
            // cause used to be dropped on the floor here, which left the only evidence of what
            // actually went wrong in an okhttp "<-- HTTP FAILED" line nobody reads off a tablet
            // bolted to a wall. Log it, and put the short form on screen: "UnknownHostException"
            // (no DNS) and "SSLHandshakeException" (expired certificate, or the device's own clock
            // being wrong) are different jobs for whoever is standing in front of it.
            is ApiResult.Offline -> {
                AppLog.w(TAG, "Pairing could not reach ${BuildConfig.API_BASE_URL}", result.cause)
                PairOutcome.Offline(
                    "Cannot reach the AMS server. Check the network and the server address." +
                        reasonHint(result.cause)
                )
            }

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
                me.realtimeCaptureEnabled?.let { store.realtimeCaptureEnabled = it }
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
