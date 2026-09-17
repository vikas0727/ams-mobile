package com.example.digi.ui.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.digi.R
import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.DigiApp
import com.example.digi.player.PlanSlide
import com.example.digi.player.PlaybackEngine
import java.io.File
import kotlin.math.abs

/**
 * Renders one zone's current slide.
 *
 * ## The black frame between videos, and why it was there
 *
 * This file used to wrap the whole slide in a `Crossfade`, which meant the outgoing `VideoSlide`
 * left the composition on every single slide change. Leaving the composition ran its
 * `DisposableEffect` and **released the player**, and the incoming one ran `remember { ExoPlayer… }`
 * and built a new one. So despite a comment claiming "one ExoPlayer per zone, reused across
 * slides", the renderer was in fact creating and destroying a player, a TextureView and a hardware
 * decoder for every clip — which is both the decoder churn that comment was worried about and the
 * source of the black gap.
 *
 * Three separate stalls were stacked on top of each other at every boundary:
 *
 *  1. the old surface being torn down and a new TextureView created, which starts empty;
 *  2. `setMediaItem` + `prepare()` opening the file and initialising a codec from cold;
 *  3. decoding forward to the first frame before anything could be shown.
 *
 * ## How it works now
 *
 * One player per zone, genuinely — hoisted above anything that recomposes per slide — holding the
 * zone's videos as an **ExoPlayer playlist**. ExoPlayer buffers the next item in a playlist while
 * the current one plays and can usually keep the same codec across the boundary, so advancing is a
 * frame-accurate cut rather than a cold start. Advancing is done with `seekToNextMediaItem()` in
 * the common case precisely because that is the path ExoPlayer has already prepared for.
 *
 * The video surface is created once and never torn down. When an image slide is up the surface is
 * alpha-hidden rather than removed, so returning to video does not pay for a new surface.
 *
 * Whether that surface is a SurfaceView or a TextureView is decided per zone and can change at
 * runtime: a TextureView is needed to alpha-hide video behind an image and to let PixelCopy see the
 * video at all, but it routes every decoded frame through the app's GPU pipeline and that is enough
 * to make 1080p judder on a 2GB box. A zone that is pure video on a screen nobody is watching gets
 * the SurfaceView. See the `needsTextureView` block below.
 *
 * `pauseAtEndOfMediaItems` is what holds the last frame when a clip is shorter than the slot the
 * operator gave it. That used to be a black rectangle for the remainder of the slot, which was
 * documented as correct; it is the slot length that is correct, not the blackness.
 *
 * ## What this deliberately gives up
 *
 * A FADE transition between two videos is now a cut. Cross-fading video to video means decoding two
 * clips at once, and these boxes have two hardware decoders in total — a three-zone layout would
 * exhaust them and fall back to software decoding, which drops frames on a wall someone is standing
 * in front of. A clean cut is what the fade was trying to hide a black frame behind anyway. Fades
 * to and from images still work, because an image costs no decoder.
 */
@Composable
fun ZoneContent(
    zoneFrame: PlaybackEngine.ZoneFrame,
    muted: Boolean,
    modifier: Modifier = Modifier,
) {
    val slide = zoneFrame.slide
    val background = parseColor(zoneFrame.zone.backgroundColor) ?: Color.Transparent
    val slides = zoneFrame.zone.slides

    Box(modifier = modifier.background(background)) {
        // The video surface lives for as long as the zone does, underneath everything else. It is
        // built from the zone's whole slide list rather than from the current slide, so it survives
        // every boundary — which is the entire point.
        val videoSlides = remember(slides) { slides.filter { it.isVideo && it.isPlayable } }
        if (videoSlides.isNotEmpty()) {
            VideoLayer(
                zoneKey = zoneFrame.zone.key,
                slides = slides,
                videoSlides = videoSlides,
                slideIndex = zoneFrame.slideIndex,
                startAtMs = zoneFrame.offsetInSlideMs,
                muted = muted,
            )
        }

        // Images composite above the video. A blank cell is a real, intended state in a cluster
        // grid — the slot still occupies its beat so the wall stays in phase — so it renders as
        // nothing rather than as an error.
        val imageSlide = slide?.takeIf { !it.isVideo && it.isPlayable }
        // Written without leaning on a smart cast through the safe call: `slide?.transition == X`
        // does not reliably narrow `slide` to non-null across Kotlin versions, and this is not a
        // place to discover that at build time on somebody else's machine.
        val transitionMs = slide
            ?.takeIf { it.transition == AmsConstants.Transition.FADE }
            ?.transitionMs
            ?.coerceIn(0, 2_000)
            ?: 0

        Crossfade(
            targetState = imageSlide,
            animationSpec = tween(durationMillis = transitionMs),
            label = "imageSlide",
        ) { current ->
            if (current != null) ImageSlide(current)
        }
    }
}

@Composable
private fun ImageSlide(slide: PlanSlide) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(File(slide.localPath!!))
            // The file is immutable once downloaded — it is keyed by an S3 object key — so Coil's
            // disk cache would only be a second copy of something already on disk.
            .diskCachePolicy(coil.request.CachePolicy.DISABLED)
            .crossfade(false)
            .build(),
        contentDescription = slide.name,
        contentScale = slide.fitMode.toContentScale(),
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * The zone's persistent video surface.
 *
 * Nothing in here is keyed on the current slide, which is what keeps the player and its surface
 * alive across boundaries. The current slide only ever moves a *position* within a playlist that
 * was loaded once.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoLayer(
    zoneKey: String,
    slides: List<PlanSlide>,
    videoSlides: List<PlanSlide>,
    slideIndex: Int,
    startAtMs: Long,
    muted: Boolean,
) {
    val context = LocalContext.current

    /**
     * Which playlist position each slide maps to.
     *
     * A zone mixes images and videos freely, so the slide index the engine reports is not the
     * ExoPlayer item index. Images simply have no entry, and that absence is what tells the layer
     * to stand down.
     */
    val itemIndexBySlide = remember(slides) {
        val map = HashMap<Int, Int>()
        var item = 0
        slides.forEachIndexed { index, candidate ->
            if (candidate.isVideo && candidate.isPlayable) {
                map[index] = item
                item++
            }
        }
        map
    }

    val player = remember(zoneKey) {
        ExoPlayer.Builder(context).build().apply {
            // The engine owns timing, not the player. REPEAT_MODE_ALL keeps the last-to-first
            // boundary inside the playlist so that wrap is buffered like any other, and
            // pauseAtEndOfMediaItems stops the player running ahead of the engine on its own.
            repeatMode = Player.REPEAT_MODE_ALL
            // Holds the final frame of a clip that finished before its slot did, instead of
            // dropping to black for the remainder.
            setPauseAtEndOfMediaItems(true)
            playWhenReady = false
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // Keyed on the FILES AND THEIR ORDER, not on the list identity: the engine rebuilds its slide
    // list on every tick, and reloading the playlist four times a second would defeat the whole
    // exercise. Order is part of the key because reordering a playlist without adding or removing
    // anything is a real edit an operator makes, and it must reload.
    val playlistKey = remember(videoSlides) { videoSlides.joinToString("|") { it.localPath.orEmpty() } }

    /*
     * Which playlist the ExoPlayer is ACTUALLY holding.
     *
     * Not the same thing as [playlistKey], which is what the engine wants it to hold — and the gap
     * between the two is where the bug lived. Loading the playlist and positioning within it were
     * two independent effects, so for a moment after a reorder the engine's item indices were being
     * applied to the media items still loaded from the previous order. The player would obediently
     * seek to "item 1" of a list that had just been replaced and show the clip that used to be
     * there, for about a second, before the next correction moved it. An advert the operator had
     * just moved or deleted would appear on the wall after they had published the change.
     *
     * Now the advance effect refuses to touch the player until this says the right playlist is in
     * it.
     */
    var loadedKey by remember(zoneKey) { mutableStateOf<String?>(null) }

    /** The inflated view, so the swap can control what is shown while it happens. */
    var playerView by remember(zoneKey) { mutableStateOf<PlayerView?>(null) }

    val targetItem = itemIndexBySlide[slideIndex]

    /*
     * Load the playlist AND land on the right position in one call.
     *
     * `setMediaItems(items, startIndex, startPositionMs)` is what makes this atomic. The two-step
     * version — replace the list, then seek — leaves a window in which the player is holding the
     * new files at a position chosen for the old ones, which is exactly the glitch above.
     *
     * `slideIndex` and `startAtMs` are read at relaunch, so they are wherever the engine is at the
     * instant the swap happens. That is the correct capture: the new playlist should pick up where
     * the loop actually is, not where it was when this composable first ran.
     */
    LaunchedEffect(playlistKey) {
        runCatching {
            val items = videoSlides.map { MediaItem.fromUri(File(it.localPath!!).toURI().toString()) }
            val startIndex = (targetItem ?: 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
            val startMs = if (startAtMs > SEEK_THRESHOLD_MS) startAtMs else 0L

            // Do not hold the outgoing frame across a REPLACEMENT.
            //
            // keepContentOnPlayerReset is right at an item boundary inside a playlist — it is what
            // bridges the gap instead of flashing black. Across a playlist replacement it is wrong,
            // because the frame being held is a clip that has just been reordered away or deleted,
            // and "videos from the previous order must not appear" is the whole requirement. A few
            // frames of the zone's background is honest; a few frames of a withdrawn advert is not.
            playerView?.setKeepContentOnPlayerReset(false)

            player.setMediaItems(items, startIndex, startMs)
            player.prepare()
            loadedKey = playlistKey
        }.onFailure { AppLog.e(TAG, "Could not load the video playlist for zone $zoneKey", it) }
    }

    /*
     * Restore the bridge once real content is on the surface again.
     *
     * Tied to the first rendered frame rather than restored immediately after the swap: setting it
     * back synchronously would re-arm the hold before the new clip had drawn anything, which is the
     * same as never having cleared it.
     */
    DisposableEffect(player, playerView) {
        val view = playerView
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                view?.setKeepContentOnPlayerReset(true)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(targetItem, loadedKey) {
        // The engine has moved on to a playlist the player has not loaded yet. Doing anything here
        // would be applying new indices to old media — the bug this guard exists to prevent.
        if (loadedKey != playlistKey) return@LaunchedEffect

        if (targetItem == null) {
            // An image is up. Pause rather than release: the surface stays alive and hidden, so
            // coming back to video costs nothing.
            runCatching { player.pause() }
            return@LaunchedEffect
        }

        runCatching {
            val count = player.mediaItemCount
            val current = player.currentMediaItemIndex

            when {
                // Already on the right clip. Correct the position only if it has genuinely drifted
                // — seeking on every tick would flush the decoder and reintroduce the very stutter
                // this is here to remove. Safe to trust now: the guard above means `current` is an
                // index into the playlist the engine is talking about.
                current == targetItem -> {
                    val expected = startAtMs
                    if (expected > SEEK_THRESHOLD_MS &&
                        abs(player.currentPosition - expected) > DRIFT_TOLERANCE_MS
                    ) {
                        player.seekTo(expected)
                    }
                }

                // The ordinary boundary, and the reason for the playlist: ExoPlayer has the next
                // item buffered, so this is a cut rather than a cold start.
                count > 0 && (current + 1) % count == targetItem -> player.seekToNextMediaItem()

                // A jump — a layout change, a restart mid-loop, or a wall member joining a loop
                // already in progress. This one does cost a seek, which is correct: it is a genuine
                // discontinuity, not a boundary.
                else -> player.seekTo(targetItem, if (startAtMs > SEEK_THRESHOLD_MS) startAtMs else 0L)
            }

            player.play()
        }.onFailure { AppLog.e(TAG, "Could not advance zone $zoneKey to item $targetItem", it) }
    }

    LaunchedEffect(muted, targetItem) {
        val slideMuted = slides.getOrNull(slideIndex)?.muted ?: false
        player.volume = if (muted || slideMuted) 0f else 1f
    }

    val fitMode = slides.getOrNull(slideIndex)?.fitMode ?: videoSlides.first().fitMode

    /* ── which surface this zone renders through ────────────────────────────
     *
     * A SurfaceView hands the decoded frames straight to the display hardware and costs the app
     * nothing per frame. A TextureView routes every frame through the app's GPU pipeline, which is
     * what makes the video capturable and alpha-composable — and what makes 1080p judder on a 2GB
     * box. This layer used to be TextureView unconditionally, so every screen in the fleet paid
     * that permanently for a screenshot feature nobody was using most of the time.
     *
     * Two things genuinely need the TextureView, and nothing else does:
     *
     *  1. Somebody is watching. Live Data View and the SCREENSHOT command both read the window with
     *     PixelCopy, and a SurfaceView is not in the window — the capture comes back black.
     *  2. The zone mixes images with video. The video layer is hidden behind an image by fading its
     *     alpha to zero, and a SurfaceView ignores alpha: the video would carry on showing through
     *     an image slide, and through the Crossfade between them.
     *
     * Neither is true for the ordinary case — a zone playing a video loop on an unwatched screen —
     * so that case gets the fast path.
     */
    val captureEnabled by DigiApp.graph(context).store.realtimeCapture.collectAsStateWithLifecycle()
    val mixesImages = remember(slides) { slides.any { !it.isVideo && it.isPlayable } }
    /*
     * Third reason, and the one that made SCREENSHOT work at last: a capture is in progress.
     *
     * A one-off screenshot never turned this on, so an ordinary video zone was captured on the
     * SurfaceView path and came back as a black rectangle. Reading that surface directly is the
     * documented answer and does not work on these boxes — it returns nothing and disturbs the live
     * picture — whereas a TextureView demonstrably works, because that is what every correct
     * screenshot taken here so far has quietly been using. See VideoSurfaces.
     */
    val capturing by VideoSurfaces.textureRequired.collectAsStateWithLifecycle()
    val wantsTextureView = captureEnabled || mixesImages || capturing

    /*
     * Latched on, never off, for as long as this zone lives.
     *
     * Going UP — SurfaceView to TextureView — is safe: the player is playing, so the new surface has
     * a frame on it within a frame or two. Coming back DOWN is not, and that is the black screen
     * after switching Live Data View off. A rebuilt surface starts empty and is filled by the next
     * decoded frame, so it only recovers if a frame is coming. Between clips, or on a short clip
     * held at its last frame by `pauseAtEndOfMediaItems`, the player is paused and no frame is ever
     * coming — the zone stays black until something else happens to move it.
     *
     * The swap could be made safe by nudging the player to re-render, but that means a seek on a
     * live wall to fix a problem that only exists because the surface was downgraded at all. Not
     * downgrading is the smaller change and it cannot fail.
     *
     * The cost, stated plainly: a zone that has once been watched or photographed keeps the more
     * expensive surface until the playlist changes or the app restarts — which is a risk of judder
     * on a 2GB box, against a guaranteed black screen the other way. The fast path still applies
     * where it matters, to every screen that is simply playing and that nobody has looked at.
     */
    var latchedTextureView by remember(zoneKey) { mutableStateOf(wantsTextureView) }
    LaunchedEffect(wantsTextureView) { if (wantsTextureView) latchedTextureView = true }
    val needsTextureView = wantsTextureView || latchedTextureView

    // `key` rather than a branch inside the factory: surface_type is read at inflation and cannot be
    // changed afterwards, so flipping it means building a new PlayerView. The ExoPlayer itself is
    // remembered on zoneKey and survives, so the swap is a surface re-attach — a single frame's
    // blink when an operator opens or closes Live Data View, which is the only time it happens.
    key(needsTextureView) {
        AndroidView(
            factory = { ctx ->
                // Inflated rather than constructed, purely to get at surface_type — see the
                // comments in those two layouts.
                val layout = if (needsTextureView) {
                    R.layout.zone_player_view_capture
                } else {
                    R.layout.zone_player_view
                }
                val view = LayoutInflater.from(ctx).inflate(layout, null) as PlayerView
                playerView = view
                view.apply {
                    useController = false
                    // Transparent background: zones overlap by design (a ticker over video is the
                    // most common signage layout) and an opaque surface would black out whatever is
                    // beneath.
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    // Hold the last rendered frame instead of blanking whenever the player is reset
                    // or its item changes. Without this the view clears itself to nothing at exactly
                    // the moment we are trying to bridge.
                    setKeepContentOnPlayerReset(true)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    this.player = player
                }
            },
            update = { view ->
                // Deliberately does NOT reassign the player. Handing a PlayerView a player detaches
                // the old surface and attaches a new one; doing that on every recomposition is a
                // black flash several times a second.
                view.resizeMode = fitMode.toResizeMode()
            },
            // Only reached when the surface type flips, or the zone leaves. Detaching the player
            // from the view being thrown away also unregisters its listener; Media3 ignores the
            // surface clear when another view has already taken over, so this cannot blank the
            // incoming one whichever order Compose disposes and creates in.
            onRelease = { view -> view.player = null },
            modifier = Modifier
                .fillMaxSize()
                // Hidden, not removed, while an image is up — the surface and its decoder survive.
                // Only meaningful on the TextureView path; a zone that gets here with a SurfaceView
                // has no images to hide behind, so this is always 1f for it.
                .alpha(if (targetItem != null) 1f else 0f),
        )
    }
}

/** The manifest's `fitMode` — the CMS offers cover / contain / fill. */
private fun String.toContentScale(): ContentScale = when (lowercase()) {
    "cover" -> ContentScale.Crop
    "fill", "stretch" -> ContentScale.FillBounds
    else -> ContentScale.Fit
}

@OptIn(UnstableApi::class)
private fun String.toResizeMode(): Int = when (lowercase()) {
    "cover" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    "fill", "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

/** `#RRGGBB` or `#AARRGGBB` from the CMS colour pickers; anything else is treated as unset. */
internal fun parseColor(value: String?): Color? {
    if (value.isNullOrBlank()) return null
    return runCatching { Color(android.graphics.Color.parseColor(value.trim())) }.getOrNull()
}

/** Below this, "join mid-clip" is really "start from the beginning", and seeking to near-zero costs
 *  an extra decoder flush that shows as a black frame at the top of every loop. */
private const val SEEK_THRESHOLD_MS = 500L

/** How far the player may drift from the engine before it is corrected. Loose on purpose: every
 *  correction is a flush, and a flush is more visible than the drift it fixes. */
private const val DRIFT_TOLERANCE_MS = 1_500L

private const val TAG = "ZoneRenderer"
