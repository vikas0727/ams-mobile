package com.example.digi.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.digi.core.AmsConstants
import com.example.digi.core.AppLog
import com.example.digi.core.DigiApp
import com.example.digi.core.PlayerHost
import com.example.digi.core.ServerClock
import com.example.digi.data.repo.ContentRepository
import com.example.digi.player.ClusterPlan
import com.example.digi.player.PlaybackEngine
import com.example.digi.player.PlaybackPlan
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the render loop and turns finished slides into proof-of-play.
 *
 * The frame is recomputed from the clock four times a second rather than advanced by a timer. That
 * is what makes playback self-correcting: a decode stall, a garbage-collection pause or a
 * five-second command execution cannot accumulate drift, because the next tick asks
 * [PlaybackEngine] where things *should* be rather than where it thinks it got to.
 *
 * Nothing is emitted unless the frame signature changes, so a two-minute video is composed once and
 * left alone rather than being torn down and rebuilt 480 times.
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = DigiApp.graph(app)

    private val _frame = MutableStateFlow<PlaybackEngine.Frame?>(null)
    val frame: StateFlow<PlaybackEngine.Frame?> = _frame.asStateFlow()

    val plan: StateFlow<PlaybackPlan?> = graph.content.plan
    val downloadState: StateFlow<ContentRepository.DownloadState> = graph.content.downloadState

    private val _blanked = MutableStateFlow(false)
    val blanked: StateFlow<Boolean> = _blanked.asStateFlow()

    /** When the current plan's loop started. Reset whenever a new plan is adopted. */
    private var baseMs: Long = ServerClock.now()
    private var currentPlanIdentity: String? = null

    /** Per zone: what is playing and since when, so a finished slide can be reported accurately. */
    private val playing = mutableMapOf<String, PlayingSlide>()

    private data class PlayingSlide(
        val mediaId: String?,
        val name: String?,
        val startedAtMs: Long,
        val expectedMs: Long,
        val slideIndex: Int,
    )

    init {
        viewModelScope.launch { renderLoop() }
    }

    private suspend fun renderLoop() {
        while (viewModelScope.isActive) {
            val plan = graph.content.plan.value
            if (plan == null || !plan.hasContent) {
                if (_frame.value != null) {
                    closeOutEverything()
                    _frame.value = null
                }
                delay(TICK_MS)
                continue
            }

            adoptIfChanged(plan)

            val now = ServerClock.now()
            val frame = PlaybackEngine.frameAt(plan, now, baseMs)
            if (frame == null) {
                delay(TICK_MS)
                continue
            }

            if (frame.signature != _frame.value?.signature) {
                onFrameChanged(plan, frame, now)
                _frame.value = frame
            }

            delay(TICK_MS)
        }
    }

    /**
     * Adopting a new plan resets the loop origin — except on a video wall, where the origin belongs
     * to the batch and not to this device.
     *
     * That distinction is the whole of cluster sync. A wall member that reset its own origin every
     * time it re-synced would restart its column while its neighbours carried on, which is exactly
     * the tearing the shared `loopOriginAt` exists to prevent.
     */
    private fun adoptIfChanged(plan: PlaybackPlan) {
        val identity = planIdentity(plan)
        if (identity == currentPlanIdentity) return

        closeOutEverything()
        currentPlanIdentity = identity
        baseMs = clusterBase(plan.cluster) ?: ServerClock.now()

        AppLog.i(
            TAG,
            "Adopted ${plan.source} plan '${plan.playlistName ?: "unnamed"}' " +
                "(${plan.layouts.size} layout(s), cycle ${plan.cycleDurationMs / 1000}s)" +
                (plan.cluster?.let { ", wall position ${it.position}, sync ${if (it.syncEnabled) "on" else "off"}" } ?: "")
        )
    }

    private fun clusterBase(cluster: ClusterPlan?): Long? {
        if (cluster == null) return null
        // Sync off means the operator is still building the grid: run this column at this device's
        // own pace rather than holding it against an origin that is not meaningful yet.
        if (!cluster.syncEnabled) return null
        return cluster.loopOriginAtMs
    }

    /**
     * The identity of a plan for the purposes of "is this the same loop I was already running".
     *
     * Deliberately not the whole object: a re-sync that only refreshed signed URLs produces a
     * structurally identical plan, and restarting the loop for that would make a screen jump back
     * to slide one every time its manifest was refreshed.
     */
    private fun planIdentity(plan: PlaybackPlan): String = buildString {
        append(plan.source).append('|')
        append(plan.playlistId ?: plan.playlistName).append('|')
        append(plan.cluster?.batchId ?: "").append('|')
        append(plan.cluster?.loopOriginAtMs ?: "").append('|')
        append(plan.layouts.size).append('|')
        plan.layouts.forEach { layout ->
            append(layout.key).append(':').append(layout.durationMs).append(':')
            layout.zones.forEach { zone ->
                append(zone.key).append('#')
                zone.slides.forEach { append(it.mediaId ?: it.cacheKey ?: "?").append(',') }
            }
        }
    }

    /**
     * A frame changed: close out every slide that just ended and open the ones that started.
     *
     * `completed` is true when the slide ran for at least 90% of its declared duration. The 10%
     * tolerance covers decoder start-up and the tick granularity; below it, something cut the slide
     * short — a layout advanced, a command interrupted playback — and a 30-second spot that ran for
     * four seconds is not a delivered impression, which is a commercial distinction rather than a
     * cosmetic one.
     */
    private fun onFrameChanged(plan: PlaybackPlan, frame: PlaybackEngine.Frame, now: Long) {
        val stillPlaying = mutableSetOf<String>()

        for (zoneFrame in frame.zones) {
            val zoneKey = zoneFrame.zone.key
            stillPlaying += zoneKey
            val slide = zoneFrame.slide

            val previous = playing[zoneKey]
            val changed = previous == null ||
                previous.slideIndex != zoneFrame.slideIndex ||
                previous.mediaId != slide?.mediaId

            if (!changed) continue

            previous?.let { close(plan, zoneKey, it, now) }

            if (slide != null && !slide.isBlank) {
                playing[zoneKey] = PlayingSlide(
                    mediaId = slide.mediaId,
                    name = slide.name,
                    startedAtMs = now,
                    expectedMs = slide.durationMs,
                    slideIndex = zoneFrame.slideIndex,
                )
                viewModelScope.launch {
                    graph.events.playlistEvent(
                        status = AmsConstants.LogStatus.PLAYING,
                        fileName = slide.name,
                        mediaId = slide.mediaId,
                        playlistId = plan.playlistId,
                    )
                }
            } else {
                playing.remove(zoneKey)
            }
        }

        // A zone that disappeared with a layout change still has a slide to close out.
        playing.keys.filterNot { it in stillPlaying }.forEach { key ->
            playing[key]?.let { close(plan, key, it, now) }
            playing.remove(key)
        }
    }

    private fun close(plan: PlaybackPlan, zoneKey: String, slide: PlayingSlide, now: Long) {
        val playedMs = (now - slide.startedAtMs).coerceAtLeast(0)
        val completed = slide.expectedMs <= 0 || playedMs >= slide.expectedMs * 0.9
        viewModelScope.launch {
            graph.proofOfPlay.record(
                mediaId = slide.mediaId,
                playedAtMillis = slide.startedAtMs,
                durationSeconds = playedMs / 1000.0,
                zoneKey = zoneKey,
                playlistId = plan.playlistId,
                deploymentId = plan.deploymentId,
                completed = completed,
            )
            if (!completed) {
                graph.events.playlistEvent(
                    status = AmsConstants.LogStatus.SKIPPED,
                    fileName = slide.name,
                    mediaId = slide.mediaId,
                    playlistId = plan.playlistId,
                )
            }
        }
    }

    /** Close every open slide — on a plan change, on losing content, and when the app goes away. */
    private fun closeOutEverything() {
        val plan = graph.content.plan.value ?: return
        val now = ServerClock.now()
        playing.forEach { (zoneKey, slide) -> close(plan, zoneKey, slide, now) }
        playing.clear()
    }

    /** What the CMS screen list shows verbatim, and what rides on every heartbeat. */
    fun currentlyPlaying(): String? {
        val frame = _frame.value ?: return null
        val main = frame.zones.firstOrNull { it.slide != null } ?: return null
        val name = main.slide?.name ?: return null
        return "${frame.layout.name ?: frame.layout.key} · $name"
    }

    /**
     * The main zone's real position, for the CMS live preview.
     *
     * "Main zone" is the first zone with something on it, which is the same choice
     * [currentlyPlaying] makes — a multi-zone layout has no single answer, and the preview is
     * showing the whole layout anyway; this is only what it synchronises ITS clock to.
     */
    fun playbackState(): PlayerHost.PlaybackState? {
        val frame = _frame.value ?: return null
        val main = frame.zones.firstOrNull { it.slide != null } ?: return null
        val slide = main.slide ?: return null
        return PlayerHost.PlaybackState(
            mediaId = slide.mediaId,
            name = slide.name,
            mediaType = slide.mediaType,
            positionMs = main.offsetInSlideMs,
            slideIndex = main.slideIndex,
            durationMs = slide.durationMs,
            // Blanked is still "playing" as far as the loop is concerned — the engine keeps
            // running so POWER_ON resumes in the right place — but nothing is on the glass, and
            // the preview must not imply otherwise.
            playing = !_blanked.value,
        )
    }

    fun setBlanked(value: Boolean) {
        _blanked.value = value
    }

    /** Manual re-sync, from the diagnostics overlay. */
    fun forceSync() {
        viewModelScope.launch {
            graph.content.invalidate()
            graph.content.sync()
        }
    }

    override fun onCleared() {
        // The app is going away mid-slide; report what actually played rather than losing it.
        closeOutEverything()
        super.onCleared()
    }

    private companion object {
        const val TAG = "PlayerVM"
        const val TICK_MS = 250L
    }
}
