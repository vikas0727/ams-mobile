package com.example.digi.ui.player

import android.view.SurfaceView
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The video SurfaceViews currently on screen, so a screenshot can find them.
 *
 * ## Why a registry is needed at all
 *
 * `PixelCopy.request(window, …)` reads the window's own surface. A SurfaceView is not part of it —
 * it is a separate surface that the display composites behind the window, showing through a hole
 * punched in it. So a window copy of a screen playing video returns everything except the video.
 *
 * That is exactly the emulator/device split. An emulator composites through SwiftShader or a host
 * GL path where the SurfaceView's content generally does land in the window copy, so a screenshot
 * taken on an emulator looks complete and the same code on a real box comes back with a black
 * rectangle where the content was.
 *
 * The renderer already switches a zone to a TextureView — which IS in the window — when Live Data
 * View is on. A one-off SCREENSHOT command does not turn that on, and should not have to: flipping
 * the surface type rebuilds the PlayerView and blinks the video on the wall, which is a poor trade
 * for a screenshot nobody standing there asked to see interrupted.
 *
 * So instead the capture reads each video surface directly — `PixelCopy` has a SurfaceView overload
 * that goes to the right surface — and draws the result into the window copy. Nothing about
 * playback changes, and the screenshot is correct whichever surface type the zone happens to be on.
 *
 * ## Lifetime
 *
 * Registered when a zone's PlayerView is created and removed when it is released, both from the
 * Compose main thread. Reads come from the capture coroutine, hence the copy-on-write list: the set
 * is tiny (one entry per video zone) and written a handful of times per playlist change.
 */
object VideoSurfaces {

    private val surfaces = CopyOnWriteArrayList<SurfaceView>()

    fun register(view: SurfaceView) {
        if (!surfaces.contains(view)) surfaces.add(view)
    }

    fun unregister(view: SurfaceView) {
        surfaces.remove(view)
    }

    /**
     * Those worth trying to copy: attached, laid out, and with a live surface.
     *
     * A detached or zero-sized view makes PixelCopy throw rather than fail politely, and a zone
     * mid-swap can briefly be either.
     */
    fun capturable(): List<SurfaceView> = surfaces.filter { view ->
        view.isAttachedToWindow &&
            view.width > 0 &&
            view.height > 0 &&
            view.holder.surface?.isValid == true
    }
}
