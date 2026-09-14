package com.example.digi.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.digi.core.AppLog
import com.example.digi.data.remote.ApiClient
import com.example.digi.data.remote.dto.SettingsDto
import com.example.digi.data.remote.dto.SyncResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything small and durable: the credential, the identity, the last manifest, the last settings.
 *
 * The manifest is cached here rather than in Room because it is one blob that is always read whole,
 * and because it is what lets a box that boots with no network still put content on the wall — the
 * single most visible offline behaviour there is. A screen that comes back from a power cut into a
 * dead uplink should show yesterday's loop, not a "no content" card.
 *
 * The token lives in EncryptedSharedPreferences where the hardware allows it. On cheap Android TV
 * SoCs the keystore is sometimes absent or broken, and a player that refuses to start because it
 * cannot encrypt a preference file is worse than one that stores a token in plain app-private
 * storage — so the fallback is deliberate and logged rather than fatal.
 */
class PlayerStore(context: Context) {

    private val app = context.applicationContext

    private val secure: SharedPreferences = try {
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "digi_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Throwable) {
        AppLog.w(TAG, "Keystore unavailable, falling back to plain preferences for the token", e)
        app.getSharedPreferences("digi_secure_plain", Context.MODE_PRIVATE)
    }

    private val prefs: SharedPreferences = app.getSharedPreferences("digi", Context.MODE_PRIVATE)

    private val _paired = MutableStateFlow(!playerToken.isNullOrBlank())

    /** Drives the top-level UI: pairing screen versus player. */
    val paired: StateFlow<Boolean> get() = _paired.asStateFlow()

    /* ── credential ──────────────────────────────────────────────────────── */

    var playerToken: String?
        get() = secure.getString(KEY_TOKEN, null)
        set(value) {
            secure.edit().apply { if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, value) }.apply()
            _paired.value = !value.isNullOrBlank()
        }

    /**
     * The hardware identity sent at pair time and baked into the token.
     *
     * Generated once and never regenerated: VerifyPlayerToken compares the id inside the token with
     * the one stored on the screen, so a device that invented a new id would authenticate fine and
     * then be rejected as "paired to a different device".
     */
    var deviceUniqueId: String?
        get() = secure.getString(KEY_DEVICE_ID, null)
        set(value) = secure.edit().putString(KEY_DEVICE_ID, value).apply()

    /* ── screen identity ─────────────────────────────────────────────────── */

    var screenId: String?
        get() = prefs.getString(KEY_SCREEN_ID, null)
        set(value) = prefs.edit().putString(KEY_SCREEN_ID, value).apply()

    var screenName: String?
        get() = prefs.getString(KEY_SCREEN_NAME, null)
        set(value) = prefs.edit().putString(KEY_SCREEN_NAME, value).apply()

    var orientation: String?
        get() = prefs.getString(KEY_ORIENTATION, null)
        set(value) = prefs.edit().putString(KEY_ORIENTATION, value).apply()

    /* ── content state ───────────────────────────────────────────────────── */

    /**
     * What this device believes it holds. Sent on every heartbeat; the server compares and answers
     * `contentStale`. Only advanced once a manifest has been successfully stored — advancing it on
     * receipt would make a failed sync look like a completed one and the screen would never retry.
     */
    var contentVersion: Int
        get() = prefs.getInt(KEY_CONTENT_VERSION, -1)
        set(value) = prefs.edit().putInt(KEY_CONTENT_VERSION, value).apply()

    /**
     * The app canvas rotation last applied, in degrees.
     *
     * Persisted so a cold start comes up the right way round straight away. Reading it out of the
     * cached settings blob instead would work, but only after the first composition — the screen
     * would show landscape for a beat and then swing, in front of whoever is standing there.
     */
    var appRotationDegrees: Int
        get() = prefs.getInt(KEY_APP_ROTATION, 0)
        set(value) = prefs.edit().putInt(KEY_APP_ROTATION, if (value in ROTATIONS) value else 0).apply()

    /**
     * The last retain list the server sent — every cache key this screen is entitled to hold.
     *
     * Persisted so the operator's DELETE_UNUSED_MEDIA button can act on the server's authority
     * rather than on a local guess, without forcing a full re-sync first. Empty means the server has
     * never sent one; an empty LIST that it did send is stored as a single blank entry so the two
     * stay distinguishable — "hold nothing" is a real instruction and must not read as "no opinion".
     */
    var retainCacheKeys: List<String>?
        get() = prefs.getStringSet(KEY_RETAIN, null)?.toList()
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_RETAIN) else putStringSet(KEY_RETAIN, value.toSet())
        }.apply()

    var heartbeatIntervalSeconds: Int
        get() = prefs.getInt(KEY_HEARTBEAT, com.example.digi.core.AmsConstants.DEFAULT_HEARTBEAT_SECONDS)
        set(value) = prefs.edit().putInt(KEY_HEARTBEAT, value.coerceIn(10, 3600)).apply()

    var lastSyncedAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    var lastHeartbeatOkAt: Long
        get() = prefs.getLong(KEY_LAST_HEARTBEAT_OK, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_HEARTBEAT_OK, value).apply()

    /**
     * Measured offset between this device's clock and the server's, in millis.
     *
     * Nobody synchronises the clock on a signage box, and cluster playback positions are computed
     * from a shared `loopOriginAt` — a panel whose clock is 40 seconds fast would sit 40 seconds
     * ahead of the rest of the wall, which is precisely the tearing cluster sync exists to prevent.
     */
    var serverTimeOffsetMs: Long
        get() = prefs.getLong(KEY_TIME_OFFSET, 0)
        set(value) = prefs.edit().putLong(KEY_TIME_OFFSET, value).apply()

    /**
     * Whether an operator has live event capture on for this screen.
     *
     * Toggled by the START/STOP_REALTIME_EVENT_CAPTURE commands. The server discards events when it
     * is off; tracking it locally means the player stops *queueing* them too, rather than filling a
     * table with rows that will be thrown away.
     */
    var realtimeCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_REALTIME_CAPTURE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_REALTIME_CAPTURE, value).apply()
            _realtimeCapture.value = value
        }

    /**
     * The same flag, observable.
     *
     * The video layer needs this, not just the reporters: a zone can only be photographed when it
     * renders through a TextureView, and a TextureView costs a GPU copy of every video frame. So
     * the player uses a SurfaceView normally and swaps to a TextureView only while somebody is
     * actually watching — which means the UI has to be told the moment the flag moves rather than
     * on its next recomposition, whenever that happens to be.
     */
    private val _realtimeCapture = MutableStateFlow(prefs.getBoolean(KEY_REALTIME_CAPTURE, false))
    val realtimeCapture: StateFlow<Boolean> get() = _realtimeCapture.asStateFlow()

    /* ── cached manifest & settings ──────────────────────────────────────── */

    fun saveManifest(raw: SyncResponse) {
        runCatching { ApiClient.json.encodeToString(SyncResponse.serializer(), raw) }
            .onSuccess { prefs.edit().putString(KEY_MANIFEST, it).apply() }
            .onFailure { AppLog.w(TAG, "Could not cache manifest", it) }
    }

    fun loadManifest(): SyncResponse? {
        val raw = prefs.getString(KEY_MANIFEST, null) ?: return null
        return runCatching { ApiClient.json.decodeFromString(SyncResponse.serializer(), raw) }
            .onFailure { AppLog.w(TAG, "Cached manifest unreadable, discarding", it) }
            .getOrNull()
    }

    fun clearManifest() = prefs.edit().remove(KEY_MANIFEST).apply()

    fun saveSettings(settings: SettingsDto?) {
        if (settings == null) return
        runCatching { ApiClient.json.encodeToString(SettingsDto.serializer(), settings) }
            .onSuccess { prefs.edit().putString(KEY_SETTINGS, it).apply() }
    }

    fun loadSettings(): SettingsDto? {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return null
        return runCatching { ApiClient.json.decodeFromString(SettingsDto.serializer(), raw) }.getOrNull()
    }

    /* ── lifecycle ───────────────────────────────────────────────────────── */

    /**
     * Forget this screen entirely.
     *
     * Called when the server says 401 (unpaired, suspended or deleted in the CMS) and from the
     * on-device reset. The cached manifest goes too: continuing to play content for a screen this
     * device is no longer authorised to be is the one thing an unpair has to stop.
     */
    fun unpair() {
        secure.edit().remove(KEY_TOKEN).apply()
        prefs.edit()
            .remove(KEY_SCREEN_ID)
            .remove(KEY_SCREEN_NAME)
            .remove(KEY_ORIENTATION)
            .remove(KEY_MANIFEST)
            .remove(KEY_SETTINGS)
            .remove(KEY_RETAIN)
            .remove(KEY_CONTENT_VERSION)
            .remove(KEY_REALTIME_CAPTURE)
            .apply()
        _paired.value = false
        // Mirrors the pref removal above. Left set, the video layer would keep rendering through a
        // TextureView for a screen nobody is watching any more.
        _realtimeCapture.value = false
    }

    private companion object {
        const val TAG = "PlayerStore"
        const val KEY_TOKEN = "player_token"
        const val KEY_DEVICE_ID = "device_unique_id"
        const val KEY_SCREEN_ID = "screen_id"
        const val KEY_SCREEN_NAME = "screen_name"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_CONTENT_VERSION = "content_version"
        const val KEY_HEARTBEAT = "heartbeat_seconds"
        const val KEY_LAST_SYNC = "last_synced_at"
        const val KEY_LAST_HEARTBEAT_OK = "last_heartbeat_ok"
        const val KEY_TIME_OFFSET = "server_time_offset"
        const val KEY_REALTIME_CAPTURE = "realtime_capture"
        const val KEY_MANIFEST = "manifest_json"
        const val KEY_SETTINGS = "settings_json"
        const val KEY_APP_ROTATION = "app_rotation_degrees"
        const val KEY_RETAIN = "retain_cache_keys"
        private val ROTATIONS = setOf(0, 90, 180, 270)
    }
}
