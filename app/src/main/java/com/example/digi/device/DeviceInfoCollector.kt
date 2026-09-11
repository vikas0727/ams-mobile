package com.example.digi.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.digi.BuildConfig
import com.example.digi.core.AppLog
import com.example.digi.core.NetworkMonitor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.NetworkInterface

/**
 * The ~45-field property dump behind the CMS "Additional Info" tab.
 *
 * Key names are chosen to match what `PlayerController.reportDeviceInfo` mirrors into telemetry —
 * `signageAppVersionName`, `sdkVersion`, `networkSsid`, `totalRamBytes`, `deviceWidth` and the
 * rest. The controller accepts either camelCase or SCREAMING_CASE for those; camelCase is used here
 * because the rest of the schemaless blob is rendered verbatim in the portal and a mixed-case table
 * reads badly.
 *
 * Sent on pair and whenever something material changes (an app update, a new IP) — never on a
 * heartbeat. Nearly every field is static, the payload is ~2KB, and a 200-screen fleet beating it
 * out every minute would be 60MB a day of unchanged data.
 *
 * Anything unavailable is reported as null rather than omitted or faked: the backend merges rather
 * than replaces, so a null tells the operator "this device cannot report that" instead of silently
 * leaving a stale value from a previous build in place.
 */
class DeviceInfoCollector(
    private val context: Context,
    private val networkMonitor: NetworkMonitor,
) {

    fun collect(deviceUniqueId: String?): JsonObject = buildJsonObject {
        /* --- identity --- */
        put("deviceUniqueId", deviceUniqueId)
        put("androidId", androidId())
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("board", Build.BOARD)
        put("hardware", Build.HARDWARE)
        put("bootloader", Build.BOOTLOADER)
        put("fingerprint", Build.FINGERPRINT)
        put("serialNumber", serialNumber())

        /* --- OS --- */
        put("osVersion", "Android ${Build.VERSION.RELEASE}")
        put("sdkVersion", Build.VERSION.SDK_INT)
        put("securityPatch", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else null)
        put("buildId", Build.ID)
        put("buildType", Build.TYPE)
        put("buildTags", Build.TAGS)
        put("buildTimeMs", Build.TIME)
        put("architecture", Build.SUPPORTED_ABIS.firstOrNull())
        put("supportedAbis", Build.SUPPORTED_ABIS.joinToString(","))

        /* --- the player app itself --- */
        put("signageAppId", context.packageName)
        put("signageAppVersionName", BuildConfig.VERSION_NAME)
        put("signageAppVersionCode", BuildConfig.VERSION_CODE)
        put("signageAppBuildDateMs", apkInstallTime())
        put("signageAppDebug", BuildConfig.DEBUG)

        /* --- display --- */
        val metrics = displayMetrics()
        put("deviceWidth", metrics?.widthPixels)
        put("deviceHeight", metrics?.heightPixels)
        put("densityDpi", metrics?.densityDpi)
        put("refreshRateHz", refreshRate())
        put("orientation", if ((metrics?.heightPixels ?: 0) > (metrics?.widthPixels ?: 0)) "portrait" else "landscape")

        /* --- memory & storage, in BYTES: the controller divides to megabytes itself --- */
        val ram = ramBytes()
        put("totalRamBytes", ram.first)
        put("usedRamBytes", ram.second)
        val rom = romBytes()
        put("totalRomBytes", rom.first)
        put("usedRomBytes", rom.second)
        put("appStorageUsedBytes", appStorageBytes())

        /* --- network --- */
        put("networkType", networkMonitor.networkType())
        put("networkSsid", wifiSsid())
        put("networkRssiDbm", wifiRssi())
        put("networkSignalStrength", wifiSignalLevel())
        put("deviceIpAddress", ipAddress())
        put("macAddress", macAddress())

        /* --- capability flags the portal shows as YES/NO --- */
        put("isDeviceRooted", yesNo(isRooted()))
        put("hasTouchscreen", yesNo(hasFeature(PackageManager.FEATURE_TOUCHSCREEN)))
        put("isLeanback", yesNo(hasFeature(PackageManager.FEATURE_LEANBACK)))
        put("isTelevision", yesNo(hasFeature("android.hardware.type.television")))
        put("canWriteSettings", yesNo(canWriteSettings()))
        put("isDeviceOwner", yesNo(DeviceController.isDeviceOwner(context)))
        put("timezone", java.util.TimeZone.getDefault().id)
        put("locale", java.util.Locale.getDefault().toString())
        put("hdmiInputCount", hdmiInputCount())
    }

    /* ── individual probes, each independently failure-tolerant ─────────────── */

    private fun androidId(): String? = runCatching {
        @Suppress("HardwareIds")
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()

    private fun serialNumber(): String? = runCatching {
        // READ_PHONE_STATE is required from API 26 and this app does not hold it; the call throws
        // rather than returning a placeholder, which is why it is wrapped rather than branched.
        @Suppress("HardwareIds", "DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else Build.SERIAL
    }.getOrNull()

    private fun apkInstallTime(): Long? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun displayMetrics(): DisplayMetrics? = runCatching {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun refreshRate(): Double? = runCatching {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.refreshRate.toDouble()
    }.getOrNull()

    /** @return total to used, in bytes. */
    private fun ramBytes(): Pair<Long?, Long?> = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        info.totalMem to (info.totalMem - info.availMem)
    }.getOrElse { null to null }

    private fun romBytes(): Pair<Long?, Long?> = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        total to (total - free)
    }.getOrElse { null to null }

    private fun appStorageBytes(): Long? = runCatching { context.filesDir.dirSize() }.getOrNull()

    @Suppress("DEPRECATION")
    private fun wifiSsid(): String? = runCatching {
        // From API 27 this needs a location permission the app deliberately does not hold: a
        // signage box has nobody present to grant a runtime permission. An operator who needs the
        // SSID can read it from the box's own settings, so the field degrades to null.
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun wifiRssi(): Int? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.connectionInfo?.rssi?.takeIf { it != 0 && it > -200 }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun wifiSignalLevel(): Int? =
        wifiRssi()?.let { runCatching { WifiManager.calculateSignalLevel(it, 5) }.getOrNull() }

    /** Walks interfaces directly: on an ethernet-attached box WifiManager reports nothing at all. */
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

    /**
     * Rooted boxes are the norm in this fleet — the Droidlogic units ship that way — and the portal
     * shows it because it changes what a REBOOT_DEVICE command can actually do.
     */
    private fun isRooted(): Boolean =
        Build.TAGS?.contains("test-keys") == true ||
            ROOT_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }

    private fun hasFeature(name: String): Boolean =
        runCatching { context.packageManager.hasSystemFeature(name) }.getOrDefault(false)

    private fun canWriteSettings(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(context) else true
    }.getOrDefault(false)

    /** Best effort — the count is only meaningful on boxes exposing the HDMI-CEC sysfs node. */
    private fun hdmiInputCount(): Int? = runCatching {
        File("/sys/class/hdmirx").listFiles()?.size
    }.getOrNull()

    private fun yesNo(value: Boolean) = if (value) "YES" else "NO"

    private fun File.dirSize(): Long =
        if (isFile) length() else (listFiles()?.sumOf { it.dirSize() } ?: 0L)

    private companion object {
        val ROOT_PATHS = listOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
        )
    }
}
