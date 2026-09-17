package com.example.digi.core

import android.content.Context
import android.os.Bundle
import com.example.digi.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Crash and usage reporting, behind one switch that can always be off.
 *
 * ## Why a facade rather than calling Firebase directly
 *
 * `google-services.json` is not in this repository, so a build can legitimately have no Firebase
 * project behind it (see the conditional plugin apply in app/build.gradle.kts). In that build the
 * Firebase classes are still on the classpath but `FirebaseApp` never initialises, and every call
 * into Crashlytics or Analytics throws `IllegalStateException`. Scattering that hazard through the
 * player — which runs unattended on a wall — is not worth the few characters saved. Everything
 * goes through here, every call is guarded, and [available] is the single answer to "is any of
 * this reaching Google".
 *
 * ## What this is FOR, and what it is not
 *
 * It is for the thing the CMS event log cannot do: tell you about a device that crashed, or that is
 * failing somewhere nobody thought to instrument, without an operator having first switched live
 * capture on for that screen. Crashes are exactly the events nobody is watching for in advance.
 *
 * It does not replace [com.example.digi.data.repo.EventReporter]. That reports into the CMS the
 * operator already has open, works on boxes with no Google Play services, and is what the Logs tab
 * reads. Analytics here is a second, coarser view for the fleet as a whole.
 *
 * ## Identity is the point
 *
 * A crash report that says "com.example.digi crashed in ZoneRenderer" is nearly useless across a
 * fleet of identical boxes. One that says WHICH screen, running WHICH playlist, at WHICH content
 * version, is a diagnosis. That is what [identify] and [setKey] are for, and why they are called
 * as soon as the screen's identity is known rather than at first crash.
 */
object FirebaseTelemetry {

    private const val TAG = "Telemetry"

    @Volatile
    private var crashlytics: FirebaseCrashlytics? = null

    @Volatile
    private var analytics: FirebaseAnalytics? = null

    /** True only when a Firebase project is configured AND initialised on this device. */
    val available: Boolean get() = crashlytics != null

    /** For the diagnostics overlay: distinguishes "not built in" from "built in but not working". */
    val configuredAtBuildTime: Boolean get() = BuildConfig.FIREBASE_CONFIGURED

    /**
     * Wire it up, or decide quietly that there is nothing to wire.
     *
     * Called once from [DigiApp]. Failure here is never fatal: a player that will not start because
     * a telemetry SDK would not initialise has turned a reporting feature into an outage.
     */
    fun init(context: Context) {
        if (!BuildConfig.FIREBASE_CONFIGURED) {
            AppLog.i(TAG, "Firebase not configured in this build — crash reporting is off")
            return
        }

        runCatching {
            // initializeApp reads the resources the google-services plugin generated. Null means
            // they are not there, which on a FIREBASE_CONFIGURED build means the file was added
            // after the last clean build — worth saying out loud rather than silently no-reporting.
            val app = FirebaseApp.initializeApp(context)
            if (app == null) {
                AppLog.w(TAG, "FirebaseApp did not initialise — no google_app_id resource. Rebuild?")
                return
            }

            crashlytics = FirebaseCrashlytics.getInstance().apply {
                // Collection is on by default; stated explicitly because it is the one setting that
                // decides whether any of this does anything, and a future manifest flag flipping it
                // silently would be very hard to notice.
                isCrashlyticsCollectionEnabled = true
                setCustomKey("app_version", BuildConfig.VERSION_NAME)
                setCustomKey("api_base_url", BuildConfig.API_BASE_URL)
            }

            analytics = FirebaseAnalytics.getInstance(context)

            AppLog.i(TAG, "Firebase telemetry active")
        }.onFailure {
            // Includes a box with no Play services, a broken keystore, and a config for a project
            // that has since been deleted. All of them mean the same thing here: no reporting, and
            // a player that carries on regardless.
            AppLog.w(TAG, "Firebase telemetry unavailable: ${it.message}")
            crashlytics = null
            analytics = null
        }
    }

    /**
     * Attach this screen's identity to everything reported from now on.
     *
     * The screen id is used as the Crashlytics user id because that is what the CMS is indexed by:
     * a crash lands in the console, the id goes into the portal's search box, and whoever is looking
     * is on the screen's own page with its logs and its playlist in front of them.
     */
    fun identify(screenId: String?, screenName: String?, deviceId: String?) {
        val reporter = crashlytics ?: return
        runCatching {
            screenId?.let { reporter.setUserId(it) }
            reporter.setCustomKey("screen_id", screenId ?: "unpaired")
            reporter.setCustomKey("screen_name", screenName ?: "unpaired")
            reporter.setCustomKey("device_id", deviceId ?: "unknown")
        }
        runCatching {
            analytics?.setUserId(screenId)
            analytics?.setUserProperty("screen_name", screenName?.take(36))
        }
    }

    /** State worth having in front of you when reading a stack trace — playlist, version, plan. */
    fun setKey(key: String, value: String?) {
        runCatching { crashlytics?.setCustomKey(key, value ?: "—") }
    }

    /**
     * A line in the crash report's breadcrumb trail.
     *
     * Crashlytics keeps the most recent of these and attaches them to whatever crash follows, which
     * turns "it died in the renderer" into "it died in the renderer, nine seconds after a playlist
     * swap, having failed two downloads". That is why [AppLog] tees into here rather than this
     * being called by hand at interesting moments — the interesting moment is rarely the one you
     * predicted.
     */
    fun breadcrumb(line: String) {
        runCatching { crashlytics?.log(line) }
    }

    /**
     * Report an error the app handled — a failed download, a sync that gave up, a command that
     * could not run.
     *
     * Non-fatal rather than a crash, because the player survived it. These are the reports that
     * matter most for signage: a box that crashes gets restarted and noticed, and a box that
     * quietly fails to download for a week does not.
     */
    fun nonFatal(tag: String, message: String, error: Throwable?) {
        val reporter = crashlytics ?: return
        runCatching {
            reporter.log("$tag: $message")
            reporter.recordException(error ?: HandledError("$tag: $message"))
        }
    }

    /**
     * An analytics event.
     *
     * Names and parameter keys are sanitised rather than trusted: Firebase silently drops an event
     * whose name is not `[a-zA-Z][a-zA-Z0-9_]{0,39}`, and the app's action constants are SCREAMING
     * _SNAKE strings that come from a server-side enum. Silently dropped telemetry is worse than
     * none, because you believe you have it.
     */
    fun event(name: String, params: Map<String, String?> = emptyMap()) {
        val tracker = analytics ?: return
        val eventName = sanitiseName(name) ?: return
        runCatching {
            val bundle = Bundle()
            params.forEach { (key, value) ->
                val paramKey = sanitiseName(key) ?: return@forEach
                if (value != null) bundle.putString(paramKey, value.take(MAX_PARAM_LENGTH))
            }
            tracker.logEvent(eventName, bundle)
        }
    }

    /**
     * Firebase's rules, applied here so a rejected name is a no-op we chose rather than a silent
     * drop on their side: letters, digits and underscores, starting with a letter, 40 characters.
     */
    private fun sanitiseName(raw: String): String? {
        val cleaned = raw.trim()
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
            .trimStart('_')
            .take(MAX_NAME_LENGTH)
        return cleaned.takeIf { it.isNotEmpty() && it.first().isLetter() }
    }

    private const val MAX_NAME_LENGTH = 40
    private const val MAX_PARAM_LENGTH = 100

    /**
     * Carrier for a non-fatal that had no exception of its own.
     *
     * Crashlytics groups by stack trace, so handing it a shared exception type with the message in
     * it keeps "download failed" reports together as one issue instead of scattering them.
     */
    private class HandledError(message: String) : Exception(message)
}
