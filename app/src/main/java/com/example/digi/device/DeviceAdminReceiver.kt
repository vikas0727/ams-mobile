package com.example.digi.device

import android.content.Context
import android.content.Intent
import com.example.digi.core.AppLog

/**
 * The admin component that device-owner provisioning binds to.
 *
 * It holds no policy of its own — every restriction is applied imperatively from
 * [DeviceController]. It exists because `DevicePolicyManager` requires a registered admin component
 * as the caller identity for `reboot()`, `setLockTaskPackages()` and the user restrictions, and
 * because without it `dpm set-device-owner` has nothing to point at.
 *
 * Provisioning, for reference, is done once per box straight after a factory reset and before any
 * account is added:
 *
 *     adb shell dpm set-device-owner com.example.digi/.device.DeviceAdminReceiver
 *
 * Boxes that skip this step still run the player; the privileged commands degrade as documented in
 * [DeviceController].
 */
class DeviceAdminReceiver : android.app.admin.DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        AppLog.i(TAG, "Device admin enabled — privileged commands are available")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        AppLog.w(TAG, "Device admin disabled — reboot, kiosk and settings lock will now degrade")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        AppLog.i(TAG, "Kiosk (lock task) entered")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        AppLog.i(TAG, "Kiosk (lock task) exited")
    }

    private companion object {
        const val TAG = "DeviceAdmin"
    }
}
