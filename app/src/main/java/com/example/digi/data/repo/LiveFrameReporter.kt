package com.example.digi.data.repo

import com.example.digi.core.AppLog
import com.example.digi.core.PlayerHost
import com.example.digi.data.local.PlayerStore
import com.example.digi.data.remote.ApiResult
import com.example.digi.data.remote.PlayerApi
import com.example.digi.data.remote.apiCall
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The Live Data View stream: a picture of the panel every few seconds, but only while an operator
 * is actually looking at it.
 *
 * This is deliberately not a video stream. An operator opening Live Data View wants to answer one
 * question — *is that screen really showing the advert, or is it frozen, black, or stuck on a
 * download?* — and a still every five seconds answers it completely. Real streaming would need a
 * signalling path, a codec pipeline and an always-on connection per screen, for a question a 40KB
 * JPEG already settles.
 *
 * Three things keep it cheap enough to leave in a 200-screen fleet:
 *
 *  - **Gated.** Nothing is captured or sent unless `realtimeCaptureEnabled` is on, which the
 *    heartbeat keeps in step with the CMS toggle. An unwatched screen costs exactly nothing.
 *  - **Downscaled during the copy, not after it.** The preview panel is about 380px wide, so the
 *    system scales the window straight into one reused 640px buffer: ~40KB on the wire, and no
 *    panel-sized bitmap allocated per frame. That second half matters as much as the first — a
 *    10MB allocation and a full-surface readback every five seconds is what used to make the video
 *    on the wall stutter the moment somebody opened Live Data View. See PlayerHost.captureFrame.
 *  - **Self-stopping.** The server re-checks the flag and answers `captureEnabled:false` when
 *    nobody is watching, which stops this without waiting for the next heartbeat. A player that
 *    missed the stop command cannot stream at a screen nobody has open.
 */
class LiveFrameReporter(
    private val api: PlayerApi,
    private val store: PlayerStore,
) {

    /**
     * Capture one frame and push it.
     *
     * @return true while the stream should continue. False means the server said nobody is
     *         watching, or there is nothing to capture.
     */
    suspend fun pushFrame(): Boolean {
        if (!store.realtimeCaptureEnabled) return false

        val host = PlayerHost.current()
        if (host == null) {
            // The player UI is not in the foreground, so there is no window to read. Not an error
            // and not a reason to stop: the Activity usually comes back.
            AppLog.d(TAG, "No foreground window to capture")
            return true
        }

        val jpeg = host.captureFrame(targetWidthPx = FRAME_WIDTH_PX, jpegQuality = FRAME_QUALITY)
        if (jpeg == null || jpeg.isEmpty()) {
            AppLog.d(TAG, "Frame capture returned nothing")
            return true
        }

        val part = MultipartBody.Part.createFormData(
            "file",
            // The server overwrites one fixed object per screen, so the name is cosmetic. It still
            // carries the timestamp, because a multipart part with no recognisable name is harder
            // to identify in a packet capture than it is worth saving four bytes on.
            "live-${System.currentTimeMillis()}.jpg",
            jpeg.toRequestBody("image/jpeg".toMediaType(), 0, jpeg.size),
        )

        return when (val result = apiCall { api.liveFrame(part) }) {
            is ApiResult.Success -> {
                if (!result.data.captureEnabled) {
                    // Authoritative: the operator closed the panel, or the toggle was never really
                    // on. Stop now rather than at the next heartbeat.
                    AppLog.i(TAG, "Server says nobody is watching — stopping the live frame stream")
                    store.realtimeCaptureEnabled = false
                    false
                } else {
                    true
                }
            }

            is ApiResult.Unauthorized -> {
                AppLog.w(TAG, "Live frame rejected: ${result.message}")
                false
            }

            else -> {
                // A dropped frame is not worth a retry — another one is due in seconds, and
                // queueing stale pictures of a screen would be worse than skipping them.
                AppLog.d(TAG, "Live frame upload failed, skipping: $result")
                true
            }
        }
    }

    private companion object {
        const val TAG = "LiveFrame"

        /** The CMS preview panel is ~380px wide; 640 leaves room for a retina display. */
        const val FRAME_WIDTH_PX = 640
        const val FRAME_QUALITY = 70
    }
}
