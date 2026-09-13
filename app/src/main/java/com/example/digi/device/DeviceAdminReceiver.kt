package com.example.digi.device

/*
 * REMOVED — delete this file.
 *
 *     git rm app/src/main/java/com/example/digi/device/DeviceAdminReceiver.kt
 *     git rm app/src/main/res/xml/device_admin.xml
 *
 * This app no longer registers a device-admin component. It existed to give lock task mode
 * (app pinning), settings blocking and a framework reboot path — and honouring the CMS's
 * `kioskMode` setting, which defaults to on, made every unprovisioned box raise Android's
 * "App is pinned" confirmation dialog on a public screen with nobody there to dismiss it.
 *
 * Locking a device down is a deployment decision (MDM, or device-owner provisioning at install
 * time), not something a player app should do to itself. REBOOT_DEVICE now uses `su` on the rooted
 * boxes and reports an honest failure elsewhere.
 *
 * The file is left in place only because the tooling writing this change cannot delete files; it is
 * referenced by nothing and is inert.
 */
