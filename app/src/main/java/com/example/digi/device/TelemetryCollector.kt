package com.example.digi.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.example.digi.BuildConfig
import com.example.digi.core.NetworkMonitor
import com.example.digi.data.remote.dto.TelemetryDto
import java.io.File
import java.net.NetworkInterface

/**
 * The small, changing half of what the device reports — sent on every heartbeat.
 *
 * Kept deliberately cheap: this runs once a minute on a box whose main job is decoding video, so
 * every probe here is a file read or a cached system service call, never anything that allocates or
 * blocks. Anything static or expensive belongs in [DeviceInfoCollector] instead, which runs on pair
 * and on change.
 */
class TelemetryCollector(
    private val context: Context,
    private val networkMonitor: NetworkMonitor,
) {

    /** Previous /proc/stat sample — CPU usage is a delta, so the first call has nothing to compare. */
    private var lastCpuIdle = 0L
    private var lastCpuTotal = 0L

    fun collect(): TelemetryDto {
        val ram = ramMb()
        val rom = romMb()
        return TelemetryDto(
            appVersion = BuildConfig.VERSION_NAME,
            osVersion = "Android ${Build.VERSION.RELEASE}",
            storageTotalMb = rom.first,
            storageFreeMb = rom.second,
            ramTotalMb = ram.first,
            ramFreeMb = ram.second,
            cpuUsagePercent = cpuUsagePercent(),
            temperatureCelsius = temperatureCelsius(),
            batteryPercent = batteryPercent(),
            networkType = networkMonitor.networkType(),
            ipAddress = ipAddress(),
            macAddress = macAddress(),
        )
    }

    private fun ramMb(): Pair<Long?, Long?> = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        (info.totalMem / MB) to (info.availMem / MB)
    }.getOrElse { null to null }

    private fun romMb(): Pair<Long?, Long?> = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        ((stat.blockCountLong * stat.blockSizeLong) / MB) to
            ((stat.availableBlocksLong * stat.blockSizeLong) / MB)
    }.getOrElse { null to null }

    /**
     * CPU load from /proc/stat.
     *
     * Readable without permission on most Android TV builds but blocked on some hardened ones,
     * where this simply returns null — the CMS renders an empty cell rather than a wrong number.
     * The first call after boot also returns null because there is no previous sample to difference
     * against; reporting the since-boot average instead would understate a box that has been
     * struggling for the last ten minutes.
     */
    private fun cpuUsagePercent(): Double? = runCatching {
        val fields = File("/proc/stat").useLines { it.firstOrNull() }
            ?.split(Regex("\\s+"))
            ?.drop(1)
            ?.mapNotNull { it.toLongOrNull() }
            ?: return null
        if (fields.size < 4) return null

        val idle = fields[3] + (fields.getOrNull(4) ?: 0)
        val total = fields.sum()

        val previousTotal = lastCpuTotal
        val previousIdle = lastCpuIdle
        lastCpuTotal = total
        lastCpuIdle = idle
        if (previousTotal == 0L) return null

        val totalDelta = (total - previousTotal).toDouble()
        val idleDelta = (idle - previousIdle).toDouble()
        if (totalDelta <= 0) return null
        (((totalDelta - idleDelta) / totalDelta) * 100).coerceIn(0.0, 100.0)
    }.getOrNull()

    /**
     * SoC temperature from the thermal sysfs nodes.
     *
     * Values arrive in millidegrees on most boards and plain degrees on a few, so anything above
     * 200 is divided — a signage box reading 45000 is at 45°C, not on fire. Zones are scanned in
     * order and the first plausible reading wins; picking the maximum would report a disabled zone
     * pegged at its sentinel value.
     */
    private fun temperatureCelsius(): Double? = runCatching {
        for (i in 0..9) {
            val file = File("/sys/class/thermal/thermal_zone$i/temp")
            if (!file.exists()) continue
            val raw = file.readText().trim().toDoubleOrNull() ?: continue
            val celsius = if (raw > 200) raw / 1000.0 else raw
            if (celsius in 1.0..150.0) return celsius
        }
        null
    }.getOrNull()

    /** Mains-powered boxes report 100 or nothing; a tablet used as a screen reports a real level. */
    private fun batteryPercent(): Int? = runCatching {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) null else (level * 100 / scale)
    }.getOrNull()

    private fun ipAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress
    }.getOrNull()

    private fun macAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .firstOrNull { it.isUp && !it.isLoopback && it.hardwareAddress != null }
            ?.hardwareAddress
            ?.joinToString(":") { "%02x".format(it) }
    }.getOrNull()

    private companion object {
        const val MB = 1024L * 1024L
    }
}
