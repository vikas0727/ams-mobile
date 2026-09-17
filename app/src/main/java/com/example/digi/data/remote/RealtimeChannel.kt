package com.example.digi.data.remote

import com.example.digi.core.AppLog
import com.example.digi.data.local.PlayerStore
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.URI

/**
 * The push nudge: "your content changed, look now".
 *
 * ## Why this exists
 *
 * The backend has emitted `content-updated` into a room called `screen_<id>` since the beginning,
 * and nothing has ever joined it — the player had no socket client at all, so every one of those
 * emits went into an empty room. Removing a playlist therefore reached a screen only when it next
 * heartbeated, which is up to a minute on a good day and was considerably worse before the loop's
 * timing was fixed.
 *
 * ## Deliberately carries nothing
 *
 * A nudge is a wake-up, never content. The player still presents its token to `/player/sync` and
 * receives the manifest over HTTPS exactly as before. That is what keeps this layer safe to lose:
 * the socket is unauthenticated by design (joining a room gets you a content-free ping), and the
 * database row remains the only source of truth.
 *
 * ## And is allowed to fail
 *
 * Station networks block WebSockets, transparent proxies eat them, and a box behind carrier NAT
 * will drop the connection every few minutes. None of that may be allowed to matter. Every failure
 * here is logged at debug and otherwise ignored, and the heartbeat continues underneath regardless —
 * so the worst case is the behaviour the fleet had before this class existed, not a broken screen.
 * Nothing in the player waits on a socket or treats its absence as an error.
 */
class RealtimeChannel(
    private val store: PlayerStore,
    private val baseUrl: String,
    /** Called when the server says this screen's content changed. */
    private val onContentChanged: () -> Unit,
    /** Called when a command has been queued for this screen. */
    private val onCommandQueued: () -> Unit,
    /** Called each time the channel comes up, so the caller can catch up on what it missed. */
    private val onReconnected: () -> Unit = {},
) {

    @Volatile
    private var socket: Socket? = null

    /**
     * Whether the push channel is actually up, and why not when it is not.
     *
     * This class is written to fail quietly — a blocked socket changes nothing about playback, so
     * every error is logged at debug and swallowed. That is right for the nudges it was built for
     * and wrong now that Live Data View rides on it: a screen whose socket never connects looks
     * identical, from the CMS and from this box, to one that is connected and simply has nothing to
     * say. Which is the whole reason live preview can work against a server on the dev machine and
     * not against the deployed one — an HTTP-only path through a proxy that does not forward
     * /socket.io/ still passes every heartbeat, so the screen shows Online throughout.
     *
     * Exposed rather than logged so the diagnostics overlay can put it on the wall, where whoever is
     * standing in front of the screen can read it.
     */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> get() = _connected.asStateFlow()

    @Volatile
    var lastError: String? = null
        private set

    /** Debug after the first: a box behind a blocking proxy would otherwise fill its log forever. */
    private var errorsLogged = 0

    fun connect() {
        val screenId = store.screenId
        if (screenId.isNullOrBlank()) {
            AppLog.d(TAG, "Not paired yet — no room to join")
            return
        }
        if (socket != null) return

        runCatching {
            val options = IO.Options.builder()
                // Long-polling first, upgrading to WebSocket if the path allows it. The other way
                // round looks faster and is worse here: on a network that silently drops WS the
                // handshake stalls for the full timeout on every attempt, where polling would have
                // connected immediately and simply stayed on polling.
                .setTransports(arrayOf("polling", "websocket"))
                .setReconnection(true)
                // Capped backoff. A fleet reconnecting in lockstep after a shared outage is a
                // thundering herd against the same server the heartbeats are hitting.
                .setReconnectionDelay(5_000)
                .setReconnectionDelayMax(60_000)
                .setRandomizationFactor(0.5)
                .build()

            val client = IO.socket(URI.create(socketOrigin(baseUrl)), options)

            client.on(Socket.EVENT_CONNECT) {
                AppLog.i(TAG, "Push channel connected; watching screen $screenId")
                _connected.value = true
                lastError = null
                errorsLogged = 0
                // Re-joined on every connect, not just the first: a reconnect is a new socket
                // server-side and it remembers no rooms.
                runCatching { client.emit(EVENT_WATCH, JSONObject().put("screenId", screenId)) }
                // Emits into the room while this screen was away are simply gone, so the queue has
                // to be checked rather than waited on.
                runCatching { onReconnected() }
            }

            client.on(EVENT_CONTENT_UPDATED) {
                AppLog.i(TAG, "Push: content changed")
                runCatching { onContentChanged() }
            }

            client.on(EVENT_COMMAND_QUEUED) {
                AppLog.i(TAG, "Push: command queued")
                runCatching { onCommandQueued() }
            }

            client.on(Socket.EVENT_DISCONNECT) {
                AppLog.d(TAG, "Push channel disconnected")
                _connected.value = false
            }
            // Connection errors are routine on these networks, so the log stays quiet after the
            // first — but the FIRST one is now a warning, because "the socket has never once
            // connected" is the answer to a question people otherwise spend an afternoon on.
            client.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val reason = args.firstOrNull()?.toString() ?: "unknown"
                _connected.value = false
                lastError = reason
                if (errorsLogged == 0) {
                    AppLog.w(
                        TAG,
                        "Push channel could not connect to ${socketOrigin(baseUrl)}: $reason — " +
                            "content nudges and live preview will not work until it does",
                    )
                }
                errorsLogged++
            }

            socket = client
            client.connect()
        }.onFailure { error ->
            // Includes the case where the library is missing entirely from a build. The player is
            // fully functional without it.
            //
            // AppLog.d takes (tag, message) only — the throwable overload is on w() and e(). This
            // stays at debug deliberately (see the class doc: a blocked socket changes nothing about
            // how the player behaves), so the cause is folded into the message rather than promoted
            // to a warning just to have somewhere to put it.
            AppLog.d(TAG, "Push channel could not start; polling only: $error")
            socket = null
        }
    }

    /**
     * Tell the server what is on the panel right now, for the CMS live preview.
     *
     * Fire-and-forget over the socket that is already open: no response is expected, nothing waits
     * on it, and a packet lost to a flaky link costs the preview one correction it will get again
     * two seconds later. That is why this rides the socket rather than an HTTP call — the whole
     * point is that it must be cheap enough to send continuously while somebody is watching.
     *
     * Silently does nothing when the socket is down, which is the correct behaviour: the CMS then
     * sees the state go stale and says the screen is not reporting, rather than showing a preview
     * that claims to be live.
     */
    fun emitPlayerState(state: JSONObject) {
        val client = socket ?: return
        runCatching {
            store.screenId?.let { id ->
                client.emit(EVENT_PLAYER_STATE, state.put("screenId", id))
            }
        }
    }

    fun disconnect() {
        runCatching {
            socket?.let { client ->
                store.screenId?.let { id ->
                    runCatching { client.emit(EVENT_LEAVE, JSONObject().put("screenId", id)) }
                }
                client.off()
                client.disconnect()
                client.close()
            }
        }
        socket = null
        _connected.value = false
    }

    /** Re-attach after pairing or unpairing, when the room to watch has changed. */
    fun reconnect() {
        disconnect()
        connect()
    }

    private companion object {
        const val TAG = "Realtime"
        const val EVENT_WATCH = "watch-screen"
        const val EVENT_PLAYER_STATE = "player-state"
        const val EVENT_LEAVE = "leave-screen"
        const val EVENT_CONTENT_UPDATED = "content-updated"
        const val EVENT_COMMAND_QUEUED = "command-queued"

        /**
         * The socket lives at the server ROOT, not under the API path.
         *
         * `BASE_URL` points at `https://host/api/v1/` because that is what Retrofit needs, and
         * handing that to Socket.IO would make it look for its handshake under `/api/v1/socket.io/`
         * and fail every time with a 404 that reads like a network fault. Strip back to the origin.
         */
        fun socketOrigin(baseUrl: String): String = runCatching {
            val uri = URI.create(baseUrl)
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$port"
        }.getOrDefault(baseUrl)
    }
}
