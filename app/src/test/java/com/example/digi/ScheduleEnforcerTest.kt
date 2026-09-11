package com.example.digi

import com.example.digi.core.AmsConstants
import com.example.digi.data.remote.dto.BrightnessScheduleDto
import com.example.digi.data.remote.dto.SettingsDto
import com.example.digi.data.remote.dto.TurnOffScheduleDto
import com.example.digi.device.ScheduleEnforcer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The schedule rules the backend's own documentation calls out as the ones most likely to be got
 * backwards — each with a test here for the same reason it has one there.
 */
class ScheduleEnforcerTest {

    private val enforcer = ScheduleEnforcer()
    private val ist = ZoneId.of("Asia/Kolkata")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, ist).toInstant().toEpochMilli()

    @Test
    fun `a turn-off row names the window the screen is OFF`() {
        // Reversed, every screen with a schedule sits dark through exactly the hours it should be
        // running — and the stored settings still look perfectly reasonable in the database.
        val settings = SettingsDto(
            screenTurnOffScheduleEnabled = AmsConstants.ACTIVE,
            screenTurnOffSchedules = listOf(TurnOffScheduleDto(days = emptyList(), startTime = "22:00", endTime = "06:00")),
            timezone = "Asia/Kolkata",
        )

        assertTrue("14:00 should be ON", enforcer.evaluate(settings, at(11, 14)).displayOn)
        assertFalse("23:00 should be OFF", enforcer.evaluate(settings, at(11, 23)).displayOn)
        assertFalse("02:00 should be OFF", enforcer.evaluate(settings, at(12, 2)).displayOn)
        assertTrue("07:00 should be ON", enforcer.evaluate(settings, at(12, 7)).displayOn)
    }

    @Test
    fun `an empty days array means every day, not no days`() {
        val settings = SettingsDto(
            screenTurnOffScheduleEnabled = AmsConstants.ACTIVE,
            screenTurnOffSchedules = listOf(TurnOffScheduleDto(days = null, startTime = "22:00", endTime = "23:00")),
            timezone = "Asia/Kolkata",
        )
        // 2026-09-07 is a Monday; check a weekday and a weekend day.
        assertFalse(enforcer.evaluate(settings, at(7, 22, 30)).displayOn)
        assertFalse(enforcer.evaluate(settings, at(12, 22, 30)).displayOn)
    }

    @Test
    fun `a midnight-crossing window applies its days to the START day`() {
        // "Sat 22:00-02:00" covers Saturday night into Sunday morning, not Saturday's small hours.
        // 2026-09-12 is a Saturday, 2026-09-13 a Sunday.
        val settings = SettingsDto(
            screenTurnOffScheduleEnabled = AmsConstants.ACTIVE,
            screenTurnOffSchedules = listOf(
                TurnOffScheduleDto(days = listOf("Sat"), startTime = "22:00", endTime = "02:00")
            ),
            timezone = "Asia/Kolkata",
        )

        assertFalse("Sat 23:00 is inside the window", enforcer.evaluate(settings, at(12, 23)).displayOn)
        assertFalse("Sun 01:00 is the tail of Saturday's window", enforcer.evaluate(settings, at(13, 1)).displayOn)
        assertTrue("Sat 01:00 belongs to Friday's window, which is not scheduled", enforcer.evaluate(settings, at(12, 1)).displayOn)
    }

    @Test
    fun `falls back to the legacy powerOffAt-powerOnAt pair`() {
        // Player builds already in the field read only these, so the CMS still writes them. This
        // player prefers the arrays but must not go dark if only the pair is present.
        val settings = SettingsDto(
            screenTurnOffScheduleEnabled = AmsConstants.ACTIVE,
            screenTurnOffSchedules = emptyList(),
            powerOffAt = "21:00",
            powerOnAt = "05:00",
            timezone = "Asia/Kolkata",
        )
        assertFalse(enforcer.evaluate(settings, at(11, 22)).displayOn)
        assertTrue(enforcer.evaluate(settings, at(11, 12)).displayOn)
    }

    @Test
    fun `multiple turn-off rows can differ by day`() {
        // The reason the arrays exist at all: a mall screen runs to 22:00 on Saturday and 18:00 on
        // Sunday, which the single legacy pair cannot express.
        val settings = SettingsDto(
            screenTurnOffScheduleEnabled = AmsConstants.ACTIVE,
            screenTurnOffSchedules = listOf(
                TurnOffScheduleDto(days = listOf("Sat"), startTime = "22:00", endTime = "23:59"),
                TurnOffScheduleDto(days = listOf("Sun"), startTime = "18:00", endTime = "23:59"),
            ),
            timezone = "Asia/Kolkata",
        )
        assertTrue("Sat 19:00 still running", enforcer.evaluate(settings, at(12, 19)).displayOn)
        assertFalse("Sun 19:00 already off", enforcer.evaluate(settings, at(13, 19)).displayOn)
    }

    @Test
    fun `brightness schedule wins inside its window`() {
        val settings = SettingsDto(
            brightness = 100,
            brightnessScheduleEnabled = AmsConstants.ACTIVE,
            brightnessSchedules = listOf(
                BrightnessScheduleDto(startTime = "06:00", endTime = "18:00", brightness = 100),
                BrightnessScheduleDto(startTime = "18:00", endTime = "06:00", brightness = 30),
            ),
            timezone = "Asia/Kolkata",
        )
        assertEquals(100, enforcer.evaluate(settings, at(11, 12)).brightness)
        assertEquals(30, enforcer.evaluate(settings, at(11, 21)).brightness)
        assertEquals(30, enforcer.evaluate(settings, at(12, 3)).brightness)
    }

    @Test
    fun `disabled schedules leave the screen on at its flat brightness`() {
        val settings = SettingsDto(
            brightness = 70,
            screenTurnOffScheduleEnabled = AmsConstants.INACTIVE,
            screenTurnOffSchedules = listOf(TurnOffScheduleDto(days = null, startTime = "00:00", endTime = "23:59")),
            brightnessScheduleEnabled = AmsConstants.INACTIVE,
            timezone = "Asia/Kolkata",
        )
        val decision = enforcer.evaluate(settings, at(11, 3))
        assertTrue(decision.displayOn)
        assertEquals(70, decision.brightness)
    }

    @Test
    fun `times are read in the screen's timezone, not the device's`() {
        val settings = SettingsDto(
            screenTurnOffScheduleEnabled = AmsConstants.ACTIVE,
            screenTurnOffSchedules = listOf(TurnOffScheduleDto(days = null, startTime = "22:00", endTime = "23:00")),
            timezone = "UTC",
        )
        // 22:30 UTC is 04:00 IST the next day. Under a UTC screen timezone the window is open.
        val utcTwentyTwoThirty = ZonedDateTime.of(2026, 9, 11, 22, 30, 0, 0, ZoneId.of("UTC"))
            .toInstant().toEpochMilli()
        assertFalse(enforcer.evaluate(settings, utcTwentyTwoThirty).displayOn)
    }

    @Test
    fun `auto restart fires once per day inside its catch-up window`() {
        val settings = SettingsDto(
            autoRestartEnabled = AmsConstants.ACTIVE,
            autoRestartAt = "03:30",
            timezone = "Asia/Kolkata",
        )
        val fresh = ScheduleEnforcer()
        assertFalse(fresh.shouldAutoRestart(settings, at(11, 3, 0)))
        assertTrue(fresh.shouldAutoRestart(settings, at(11, 3, 30)))
        // The service ticks every ten seconds; a second match in the same minute would restart in a
        // loop for a full minute.
        assertFalse(fresh.shouldAutoRestart(settings, at(11, 3, 31)))
    }

    @Test
    fun `an unknown timezone falls back instead of throwing`() {
        val settings = SettingsDto(
            screenTurnOffScheduleEnabled = AmsConstants.ACTIVE,
            screenTurnOffSchedules = listOf(TurnOffScheduleDto(days = null, startTime = "22:00", endTime = "23:00")),
            timezone = "Mars/Olympus_Mons",
        )
        // Must not throw — a typo in one screen's settings cannot be allowed to crash its player.
        assertTrue(enforcer.evaluate(settings, at(11, 12)).displayOn)
    }
}
