package com.example.digi.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Makes the video capturable for as long as a screenshot takes, and no longer.
 *
 * ## Why not just read the surface
 *
 * `PixelCopy.request(window, …)` reads the window's own surface, and a SurfaceView is not in it — it
 * is composited behind the window, showing through a punched hole — so a window copy of a zone
 * playing through a SurfaceView has a black rectangle where the video is.
 *
 * The obvious answer is PixelCopy's SurfaceView overload, reading that surface directly. It was
 * tried twice here and does not work on this fleet's hardware: the copy comes back empty, and worse,
 * asking for it disturbs the live surface — the panel itself went black, not only the screenshot.
 * An API that is correct in the documentation and wrong on the device in front of you is wrong.
 *
 * ## What is actually known to work
 *
 * A TextureView IS part of the window, so a window copy contains it. That is not a theory about this
 * fleet — it is the observed behaviour: screenshots came out correct for weeks, in every case where
 * Live Data View happened to be on, because that switches the zone to a TextureView for exactly this
 * reason. The mechanism was already proven; it was just never turned on for a plain screenshot.
 *
 * So a capture asks for one, waits for it, and gives it back. The cost is a brief blink on the wall
 * while the surface is rebuilt, which is the honest price of a correct screenshot and is paid only
 * when an operator asks for one. A screen already on a TextureView — anything with Live Data View on,
 * or a zone mixing images with video — pays nothing at all, because the flag is already satisfied.
 */
object VideoSurfaces {

    private val _textureRequired = MutableStateFlow(false)

    /** Collected by the renderer; true means "use a TextureView even if you would rather not". */
    val textureRequired: StateFlow<Boolean> get() = _textureRequired.asStateFlow()

    /**
     * Run [block] with the video forced onto a capturable surface.
     *
     * The flag is cleared in a `finally` so a failed or cancelled capture cannot strand the fleet on
     * TextureViews — that is the expensive surface, and leaving it on permanently is the performance
     * regression this whole mechanism exists to avoid.
     */
    suspend fun <T> forCapture(block: suspend () -> T): T {
        _textureRequired.value = true
        return try {
            block()
        } finally {
            _textureRequired.value = false
        }
    }
}
