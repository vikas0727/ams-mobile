package com.example.digi.core

import com.example.digi.data.local.PlayerStore
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The server's clock, as this device best understands it.
 *
 * Every player response carries `serverTime`. Comparing it with the local clock at the moment of
 * receipt gives an offset the rest of the app adds to `System.currentTimeMillis()`, and everything
 * time-sensitive uses [now] rather than the system clock:
 *
 *  - **cluster sync**, where members compute their loop position from a shared `loopOriginAt`. A
 *    panel forty seconds fast sits forty seconds ahead of the wall — exactly the tearing the
 *    feature exists to prevent.
 *  - **proof-of-play timestamps**, which become advertiser-facing billing evidence.
 *  - **schedules**, where a turn-off rule of "22:00" must mean 22:00 on the wall, not 22:00 by a
 *    clock that drifted an hour after a power cut.
 *
 * The offset is smoothed rather than replaced outright: a single slow response would otherwise
 * yank the clock by its own round-trip time, and a jump backwards mid-loop makes a cluster member
 * replay a slot its neighbours have already finished.
 */
object ServerClock {

    private const val TAG = "ServerClock"

    /** Ignore an apparent jump smaller than this — it is round-trip noise, not drift. */
    private const val NOISE_MS = 1_500L

    /** A correction larger than this is applied whole; anything smaller is eased in. */
    private const val SNAP_MS = 30_000L

    @Volatile
    private var offsetMs: Long = 0

    private var store: PlayerStore? = null

    fun attach(store: PlayerStore) {
        this.store = store
        offsetMs = store.serverTimeOffsetMs
    }

    /** Epoch millis on the server's timeline. */
    fun now(): Long = System.currentTimeMillis() + offsetMs

    fun nowInstant(): Instant = Instant.ofEpochMilli(now())

    val currentOffsetMs: Long get() = offsetMs

    /**
     * Fold a `serverTime` from a response into the offset.
     *
     * @param serverTimeIso the ISO-8601 string the server sent
     * @param receivedAtLocal local millis when the response arrived
     */
    fun observe(serverTimeIso: String?, receivedAtLocal: Long = System.currentTimeMillis()) {
        // `syncTimeFromServer` is honoured HERE rather than at each of the four call sites, so a
        // new one cannot be added that quietly ignores the setting. Off means the site runs its own
        // time source — an NTP-disciplined box, or a network that deliberately holds a local
        // offset — and a player second-guessing that would fight it every heartbeat.
        //
        // Null settings (a device that has never synced) default to ON, matching the CMS default:
        // a screen with a wrong clock and no settings yet is the case time sync exists for.
        if (store?.loadSettings()?.syncTimeFromServer == AmsConstants.INACTIVE) return

        val serverMs = parseIsoMillis(serverTimeIso) ?: return
        val observed = serverMs - receivedAtLocal
        val delta = observed - offsetMs
        if (kotlin.math.abs(delta) < NOISE_MS) return

        offsetMs = if (kotlin.math.abs(delta) > SNAP_MS) {
            AppLog.i(TAG, "Clock correction ${delta}ms applied in full (device clock is badly out)")
            observed
        } else {
            // Ease in: a quarter of the difference per observation converges within a few
            // heartbeats without any single sample being able to jolt playback.
            offsetMs + delta / 4
        }
        store?.serverTimeOffsetMs = offsetMs
    }

    /** ISO-8601 UTC with millis — the shape Joi's `Joi.date()` and Mongoose both accept. */
    fun isoUtc(epochMillis: Long = now()): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))

    /**
     * Parse whatever the backend sent.
     *
     * Mongoose serialises Dates as `2026-09-11T04:37:00.000Z`, but a field that was stored as a
     * string round-trips as whatever was written, and this codebase has a `convertDatesToIST`
     * helper in its response path — so an offset-bearing local time shows up too. Both are handled;
     * an unparseable value returns null rather than throwing, because a bad timestamp on one slide
     * must not take down a sync.
     */
    fun parseIsoMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                ZonedDateTime.parse(value).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                try {
                    // Last resort: a naive local timestamp, interpreted in the device's own zone.
                    java.time.LocalDateTime.parse(value)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                    AppLog.w(TAG, "Unparseable timestamp from server: $value")
                    null
                }
            }
        }
    }
}
