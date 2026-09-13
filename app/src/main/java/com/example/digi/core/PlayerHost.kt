package com.example.digi.core

import java.io.File

/**
 * The things only a live Activity can do, exposed to the background service.
 *
 * Window brightness and screen capture both need a window and an Activity context; the service
 * that executes commands has neither. Rather than leak an Activity reference into the service,
 * [com.example.digi.MainActivity] registers itself here while it is resumed and clears itself when
 * it is not.
 *
 * Every method is therefore callable while nothing is registered, and the command executor treats
 * that as a real, reportable outcome rather than a crash: "the player UI was not in the foreground"
 * is genuinely why a screenshot failed, and an operator reading the command history deserves to see
 * that instead of a generic error.
 */
interface PlayerHost {

    /**
     * Capture the window.
     *
     * @param maxWidthPx  scale the result down to at most this wide, keeping aspect. Null keeps
     *                    full resolution. The Live Data View preview is a ~380px box, so sending
     *                    1920px frames every few seconds would be a hundredfold waste of a station
     *                    uplink for pixels nobody sees.
     * @param jpegQuality 1-100 to encode as JPEG; null encodes lossless PNG. Live frames are JPEG
     *                    (~40KB) because they are transient; an operator-requested screenshot is
     *                    PNG because it goes in the history and may be read closely.
     * @return the captured file, or null if the window could not be read.
     */
    suspend fun captureScreenshot(maxWidthPx: Int? = null, jpegQuality: Int? = null): File?

    /** The fallback when WRITE_SETTINGS is not granted: dim this app's own window. */
    fun applyWindowBrightness(level: Int)

    /** POWER_OFF with no CEC control available: render black and mute, but keep the loop running so
     *  POWER_ON resumes in the right place rather than restarting it. */
    fun setBlanked(blanked: Boolean)

    /** What is on the wall right now, in the form the CMS screen list shows verbatim. */
    fun currentlyPlaying(): String?

    companion object {
        @Volatile
        private var registered: PlayerHost? = null

        fun register(host: PlayerHost) {
            registered = host
        }

        fun unregister(host: PlayerHost) {
            // Compared by identity so a new Activity registering during a configuration change
            // cannot be unregistered by the outgoing one's onPause.
            if (registered === host) registered = null
        }

        fun current(): PlayerHost? = registered
    }
}
