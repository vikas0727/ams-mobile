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

    /**
     * Turn the app's canvas by 0, 90, 180 or 270 degrees.
     *
     * Drawn rotated rather than handed to `requestedOrientation`, which is advisory: plenty of TV
     * boxes lock themselves to landscape and ignore it, and the ones that honour it recreate the
     * Activity, which restarts playback in front of whoever is watching.
     */
    fun applyAppRotation(degrees: Int)

    /** POWER_OFF with no CEC control available: render black and mute, but keep the loop running so
     *  POWER_ON resumes in the right place rather than restarting it. */
    fun setBlanked(blanked: Boolean)

    /** What is on the wall right now, in the form the CMS screen list shows verbatim. */
    fun currentlyPlaying(): String?

    /**
     * What is on the panel right now, precisely enough for the CMS to play the same thing.
     *
     * This is what makes the CMS preview LIVE rather than a simulation. The browser holds the same
     * manifest and could compute a position from the clock on its own — and used to — but that only
     * ever shows what the screen SHOULD be playing. It cannot tell that the app restarted ten
     * seconds ago, that an asset never downloaded and is being skipped, or that the panel is sitting
     * on a "no content" card. Reporting the real slide and the real offset closes that gap, and it
     * costs the device a few hundred bytes rather than a frame capture.
     *
     * Null when nothing is playing — which is itself the answer, and the CMS says so instead of
     * playing content the wall is not showing.
     */
    fun playbackState(): PlaybackState?

    /**
     * Restart the playback component — decoders and surfaces — WITHOUT restarting the process.
     *
     * This is what the CMS's "Restart Player App" button should do. Killing and relaunching the app
     * takes a signage box through a cold start in front of whoever is standing at it, and throws
     * away the socket, the local queues and the in-flight heartbeat along the way. A stuck clip, a
     * black zone or a wedged decoder needs the players rebuilt, which is all this does.
     *
     * Returns false when there is no foreground UI to restart, so the caller can fall back to a real
     * process restart rather than acknowledging a command that did nothing.
     */
    fun restartPlayback(): Boolean

    /**
     * @param mediaId      the media the main zone is showing
     * @param positionMs   how far into that slide the device actually is
     * @param slideIndex   position in the zone's slide list, so the CMS can follow a loop that has
     *                     drifted from the arithmetic rather than snapping back to it
     * @param durationMs   the slide's full length, so the CMS can tell a seek from a boundary
     * @param playing      false while blanked by a schedule or a POWER_OFF command
     */
    data class PlaybackState(
        val mediaId: String?,
        val name: String?,
        val mediaType: String?,
        val positionMs: Long,
        val slideIndex: Int,
        val durationMs: Long,
        val playing: Boolean,
    )

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
