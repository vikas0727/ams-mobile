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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.digi.R
import com.example.digi.core.AppLog
import com.example.digi.player.PlanSlide
import com.example.digi.player.PlaybackEngine
import java.io.File

/**
 * Renders one zone's current slide.
 *
 * Two things here are less obvious than they look:
 *
 *  - **The video seeks on entry.** `offsetInSlideMs` is not zero when a wall member joins a loop
 *    already in progress, or when the app restarts mid-clip. Starting from the beginning instead
 *    would put this panel out of step with the rest of the wall for the length of the clip — which
 *    is exactly the failure cluster sync exists to prevent.
 *  - **One ExoPlayer per zone, reused across slides.** Creating a player per slide leaks decoders
 *    on cheap SoCs with two hardware decoders in total; a three-zone layout with video in two of
 *    them would exhaust them within a loop or two and fall back to software decoding, which on a
 *    G96MAX means dropped frames on a wall someone is standing in front of.
 */
@Composable
fun ZoneContent(
    zoneFrame: PlaybackEngine.ZoneFrame,
    muted: Boolean,
    modifier: Modifier = Modifier,
) {
    val slide = zoneFrame.slide
    val background = parseColor(zoneFrame.zone.backgroundColor) ?: Color.Transparent

    Box(modifier = modifier.background(background)) {
        if (slide == null || slide.isBlank || slide.localPath == null) {
            // A blank cell is a real, intended state in a cluster grid — the slot still occupies
            // its beat so the wall stays in phase — so it renders as nothing, not as an error.
            return@Box
        }

        val transitionMs = if (slide.transition == com.example.digi.core.AmsConstants.Transition.FADE) {
            slide.transitionMs.coerceIn(0, 2_000)
        } else 0

        Crossfade(
            targetState = slide,
            animationSpec = tween(durationMillis = transitionMs),
            label = "slide",
        ) { current ->
            if (current.isVideo) {
                VideoSlide(
                    slide = current,
                    startAtMs = zoneFrame.offsetInSlideMs,
                    muted = muted || current.muted,
                )
            } else {
                ImageSlide(current)
            }
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

@OptIn(UnstableApi::class)
@Composable
private fun VideoSlide(slide: PlanSlide, startAtMs: Long, muted: Boolean) {
    val context = LocalContext.current

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            // The engine owns timing; a video that ends early leaves its zone black for the
            // remainder of its slot, which is correct — the slot length is what the operator set.
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(slide.localPath, startAtMs) {
        runCatching {
            player.setMediaItem(MediaItem.fromUri(File(slide.localPath!!).toURI().toString()))
            player.prepare()
            // Only seek when genuinely joining mid-clip: seeking to zero forces an extra decoder
            // flush on some boards and shows as a black frame at the start of every loop.
            if (startAtMs > 500) player.seekTo(startAtMs)
            player.volume = if (muted) 0f else 1f
            player.play()
        }.onFailure { AppLog.e(TAG, "Could not start ${slide.name}", it) }
    }

    LaunchedEffect(muted) {
        player.volume = if (muted) 0f else 1f
    }

    AndroidView(
        factory = { ctx ->
            // Inflated rather than constructed, purely to get surface_type="texture_view" — see the
            // comment in that layout. A SurfaceView is composited outside the app window, so remote
            // screenshots of a playing screen come back black.
            val view = LayoutInflater.from(ctx)
                .inflate(R.layout.zone_player_view, null) as PlayerView
            view.apply {
                useController = false
                // Transparent background: zones overlap by design (a ticker over video is the most
                // common signage layout) and an opaque surface would black out whatever is beneath.
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                resizeMode = slide.fitMode.toResizeMode()
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                this.player = player
            }
        },
        update = { view ->
            view.resizeMode = slide.fitMode.toResizeMode()
            view.player = player
        },
        modifier = Modifier.fillMaxSize(),
    )
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

private const val TAG = "ZoneRenderer"
