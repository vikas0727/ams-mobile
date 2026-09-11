package com.example.digi.device

import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.ServerClock
import com.example.digi.data.remote.dto.SettingsDto
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The screen's own schedules: when it should be dark, how bright it should be, and when to restart.
 *
 * This is the player half of the CMS's multi-row schedule work. The backend keeps the legacy single
 * `powerOnAt`/`powerOffAt` pair mirrored from row one precisely so that a player build that knows
 * nothing about the arrays keeps working — this one reads the arrays and falls back to the pair, so
 * every row an operator adds takes effect.
 *
 * Three rules that are easy to get backwards and each have a comment where they are implemented:
 *
 *  1. A turn-off row names the window the screen is **OFF**. Its `startTime` is `powerOffAt`.
 *     Reversed, a screen sits dark through exactly the hours it should be running and the stored
 *     settings still look perfectly reasonable.
 *  2. An empty `days` array means **every day**, not no days. A rule with nothing selected that
 *     silently never fired would be indistinguishable from a bug.
 *  3. Times are wall-clock in `settings.timezone`, evaluated against the server-corrected clock. A
 *     box whose own clock drifted an hour after a power cut must still go dark at 22:00 local.
 */
class ScheduleEnforcer {

    /** What the schedules say the screen should be doing at a given instant. */
    data class Decision(
        val displayOn: Boolean,
        val brightness: Int?,
        /** The rule that decided it, for the diagnostics overlay and the CMS event log. */
        val reason: String,
    )

    private var lastAutoRestartDay: String? = null

    fun evaluate(settings: SettingsDto?, atMillis: Long = ServerClock.now()): Decision {
        if (settings == null) return Decision(displayOn = true, brightness = null, reason = "no settings")

        val zone = resolveZone(settings.timezone)
        val local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)

        val off = inTurnOffWindow(settings, local)
        if (off != null) {
            return Decision(displayOn = false, brightness = 0, reason = off)
        }

        val brightness = scheduledBrightness(settings, local)
        return Decision(
            displayOn = true,
            brightness = brightness?.first ?: settings.brightness,
            reason = brightness?.second ?: "no brightness rule",
        )
    }

    /**
     * @return a description of the matching off-window, or null when the screen should be on.
     */
    private fun inTurnOffWindow(settings: SettingsDto, local: ZonedDateTime): String? {
        if (settings.screenTurnOffScheduleEnabled != AmsConstants.ACTIVE) return null

        val rows = settings.screenTurnOffSchedules.orEmpty()
            .mapNotNull { row ->
                val start = parseTime(row.startTime) ?: return@mapNotNull null
                val end = parseTime(row.endTime) ?: return@mapNotNull null
                Triple(row.days.orEmpty(), start, end)
            }
            .ifEmpty {
                // Fall back to the legacy mirrored pair. powerOffAt starts the off-window and
                // powerOnAt ends it — see rule 1 in the class comment.
                val start = parseTime(settings.powerOffAt)
                val end = parseTime(settings.powerOnAt)
                if (start != null && end != null) listOf(Triple(emptyList(), start, end)) else emptyList()
            }

        for ((days, start, end) in rows) {
            if (matches(days, start, end, local)) {
                return "turn-off window ${fmt(start)}–${fmt(end)}" +
                    (if (days.isEmpty()) " (every day)" else " (${days.joinToString(",")})")
            }
        }
        return null
    }

    /**
     * @return brightness and the rule that set it, or null when no rule applies.
     */
    private fun scheduledBrightness(settings: SettingsDto, local: ZonedDateTime): Pair<Int, String>? {
        if (settings.brightnessScheduleEnabled != AmsConstants.ACTIVE) return null

        val rows = settings.brightnessSchedules.orEmpty()
            .mapNotNull { row ->
                val start = parseTime(row.startTime) ?: return@mapNotNull null
                val end = parseTime(row.endTime) ?: return@mapNotNull null
                val level = row.brightness ?: return@mapNotNull null
                Triple(start, end, level)
            }
            .ifEmpty {
                // Legacy day/night pair, mirrored from rows [0] and [1].
                val dayStart = parseTime(settings.dayStartsAt)
                val nightStart = parseTime(settings.nightStartsAt)
                if (dayStart != null && nightStart != null) {
                    listOf(
                        Triple(dayStart, nightStart, settings.dayBrightness ?: 100),
                        Triple(nightStart, dayStart, settings.nightBrightness ?: 40),
                    )
                } else emptyList()
            }

        // First match wins. Overlapping rows are an operator's problem to notice, and picking the
        // brightest or the last would both be arbitrary — first is at least predictable from the
        // order the rows are shown in the portal.
        for ((start, end, level) in rows) {
            if (matches(emptyList(), start, end, local)) {
                return level.coerceIn(0, 100) to "brightness rule ${fmt(start)}–${fmt(end)} → $level%"
            }
        }
        return null
    }

    /**
     * Does [local] fall inside this window?
     *
     * `end` earlier than `start` means the window crosses midnight, and the day filter applies to
     * the START day — a "Sat 22:00–02:00" rule covers Saturday night into Sunday morning, not
     * Saturday's small hours. This matches how the backend's deployment scheduler reads the same
     * shape, and disagreeing with it would make a screen and its schedule preview differ.
     */
    private fun matches(days: List<String>, start: LocalTime, end: LocalTime, local: ZonedDateTime): Boolean {
        val time = local.toLocalTime()
        val crossesMidnight = end < start

        return if (!crossesMidnight) {
            time >= start && time < end && dayAllowed(days, local.dayOfWeek)
        } else {
            when {
                // Evening portion: today is the start day.
                time >= start -> dayAllowed(days, local.dayOfWeek)
                // Small-hours portion: the window started yesterday, so yesterday is the start day.
                time < end -> dayAllowed(days, local.dayOfWeek.minus(1))
                else -> false
            }
        }
    }

    /** Empty means every day — rule 2. */
    private fun dayAllowed(days: List<String>, day: DayOfWeek): Boolean {
        if (days.isEmpty()) return true
        val code = AmsConstants.DAYS.getOrNull(day.value - 1) ?: return true
        return days.any { it.equals(code, ignoreCase = true) }
    }

    /**
     * Should the app restart right now?
     *
     * Fires at most once per local day: a minute-resolution match would otherwise trigger on every
     * tick inside the same minute, and a player that restarts in a loop for sixty seconds is worse
     * than one that never restarts at all.
     */
    fun shouldAutoRestart(settings: SettingsDto?, atMillis: Long = ServerClock.now()): Boolean {
        if (settings?.autoRestartEnabled != AmsConstants.ACTIVE) return false
        val target = parseTime(settings.autoRestartAt) ?: return false

        val zone = resolveZone(settings.timezone)
        val local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)
        val today = local.toLocalDate().toString()
        if (lastAutoRestartDay == today) return false

        val now = local.toLocalTime()
        // A two-minute catch-up window: the service ticks once a minute, and a box that was asleep
        // or busy decoding through the exact minute would otherwise skip the day entirely.
        val due = now >= target && now < target.plusMinutes(2)
        if (due) {
            lastAutoRestartDay = today
            AppLog.i(TAG, "Auto-restart due at ${fmt(target)} ${zone.id}")
        }
        return due
    }

    private fun resolveZone(id: String?): ZoneId = runCatching {
        ZoneId.of(id ?: DEFAULT_ZONE)
    }.getOrElse {
        AppLog.w(TAG, "Unknown timezone '$id', falling back to $DEFAULT_ZONE")
        ZoneId.of(DEFAULT_ZONE)
    }

    /** Accepts "HH:mm" and tolerates "H:mm" and "HH:mm:ss", which the CMS has produced both of. */
    private fun parseTime(value: String?): LocalTime? {
        if (value.isNullOrBlank()) return null
        val parts = value.trim().split(":")
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    private fun fmt(time: LocalTime) = "%02d:%02d".format(time.hour, time.minute)

    private companion object {
        const val TAG = "ScheduleEnforcer"
        const val DEFAULT_ZONE = "Asia/Kolkata"
    }
}
