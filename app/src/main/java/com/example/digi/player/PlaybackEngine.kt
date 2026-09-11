package com.example.digi.player

import kotlin.random.Random

/**
 * Where in the loop are we, right now?
 *
 * A pure function of (plan, instant). Nothing here holds state, starts a timer or touches a
 * player — which matters for two reasons beyond testability:
 *
 *  - **Self-correcting.** The renderer asks this four times a second rather than advancing a
 *    counter of its own. A decode stall, a garbage-collection pause or a five-second command
 *    execution cannot make playback drift, because the next tick recomputes the truth from the
 *    clock instead of from where it thinks it got to.
 *  - **Cluster-correct.** A wall member passes `loopOriginAt` as the base and gets the position its
 *    neighbours are at, including immediately after a reboot. There is no separate code path for
 *    it; it is the same arithmetic with a different origin.
 */
object PlaybackEngine {

    /** Everything on screen at one instant. */
    data class Frame(
        val cycleIndex: Long,
        val layoutIndex: Int,
        val layout: PlanLayout,
        val offsetInLayoutMs: Long,
        val zones: List<ZoneFrame>,
    ) {
        /**
         * Changes exactly when something must be re-rendered, and not on every tick. The renderer
         * keys off this rather than diffing the frame, so a video is not torn down and re-created
         * four times a second.
         */
        val signature: String
            get() = buildString {
                append(cycleIndex).append('|').append(layoutIndex)
                zones.forEach { append('|').append(it.zone.key).append(':').append(it.slideIndex) }
            }
    }

    data class ZoneFrame(
        val zone: PlanZone,
        val slideIndex: Int,
        val slide: PlanSlide?,
        /** How far into this slide we are — what a video seeks to when it starts or restarts. */
        val offsetInSlideMs: Long,
        val remainingMs: Long,
    )

    /**
     * @param plan    what to play
     * @param nowMs   the server-corrected instant
     * @param baseMs  when the loop started: `loopOriginAt` for a cluster member, or the moment this
     *                plan was adopted for anything else
     */
    fun frameAt(plan: PlaybackPlan, nowMs: Long, baseMs: Long): Frame? {
        val layouts = plan.layouts.filter { it.zones.any { z -> z.slides.isNotEmpty() } }
        if (layouts.isEmpty()) return null

        val cycleMs = layouts.sumOf { it.durationMs }.coerceAtLeast(1L)
        val elapsed = nowMs - baseMs

        // Before the origin: a cluster manifest's loopOriginAt is deliberately a few seconds in the
        // future so every member has the manifest before anyone starts. Hold on the first frame
        // rather than running the modulo into negative territory, which would put this panel at a
        // pseudo-random point in the loop for those few seconds — visibly worse than a brief hold.
        val position = if (elapsed < 0) 0L else elapsed % cycleMs
        val cycleIndex = if (elapsed < 0) 0L else elapsed / cycleMs

        // A non-looping playlist stops on its last frame rather than restarting. `loop` is the
        // operator's explicit choice and a screen that ignores it replays an announcement all day.
        if (!plan.loop && cycleIndex > 0) {
            val last = layouts.last()
            return Frame(
                cycleIndex = 0,
                layoutIndex = layouts.lastIndex,
                layout = last,
                offsetInLayoutMs = last.durationMs,
                zones = last.zones.map { zone ->
                    val slides = orderedSlides(zone, plan.shuffle, 0)
                    val index = (slides.size - 1).coerceAtLeast(0)
                    ZoneFrame(zone, index, slides.getOrNull(index), 0, 0)
                },
            )
        }

        var cursor = position
        var layoutIndex = 0
        for ((index, layout) in layouts.withIndex()) {
            if (cursor < layout.durationMs) {
                layoutIndex = index
                break
            }
            cursor -= layout.durationMs
            layoutIndex = index
        }
        val layout = layouts[layoutIndex]
        val offsetInLayout = cursor.coerceIn(0, layout.durationMs)

        val zones = layout.zones.map { zone ->
            zoneFrame(zone, offsetInLayout, plan.shuffle, cycleIndex)
        }

        return Frame(cycleIndex, layoutIndex, layout, offsetInLayout, zones)
    }

    /**
     * A zone runs its own slide list independently of the layout's length: shorter zones loop
     * inside the layout, longer ones are cut off when the layout advances.
     *
     * This is the behaviour the backend documents and it is what makes a ticker of four short
     * messages work underneath a single two-minute video, which is the most common signage layout
     * there is.
     */
    private fun zoneFrame(
        zone: PlanZone,
        offsetInLayoutMs: Long,
        shuffle: Boolean,
        cycleIndex: Long,
    ): ZoneFrame {
        val slides = orderedSlides(zone, shuffle, cycleIndex)
        if (slides.isEmpty()) return ZoneFrame(zone, -1, null, 0, 0)

        val total = slides.sumOf { it.durationMs }.coerceAtLeast(1L)
        var cursor = offsetInLayoutMs % total

        for ((index, slide) in slides.withIndex()) {
            if (cursor < slide.durationMs) {
                return ZoneFrame(
                    zone = zone.copy(slides = slides),
                    slideIndex = index,
                    slide = slide,
                    offsetInSlideMs = cursor,
                    remainingMs = slide.durationMs - cursor,
                )
            }
            cursor -= slide.durationMs
        }
        // Only reachable through floating-point rounding in the durations; land on the last slide
        // rather than returning nothing and blanking the zone.
        val lastIndex = slides.lastIndex
        return ZoneFrame(zone.copy(slides = slides), lastIndex, slides[lastIndex], 0, 0)
    }

    /**
     * Shuffle is seeded by the cycle index, not by a random source.
     *
     * That makes the order identical on every device for a given pass of the loop, which is what a
     * shuffled playlist on a video wall needs — three panels each shuffling independently would
     * show three unrelated files side by side. It also means the order survives a restart mid-loop.
     */
    private fun orderedSlides(zone: PlanZone, shuffle: Boolean, cycleIndex: Long): List<PlanSlide> {
        // Assets that have not downloaded yet are skipped rather than rendered as a gap: a blank
        // beat looks like a fault, a slightly shorter loop does not.
        val playable = zone.slides.filter { it.isPlayable || it.isBlank }
        if (!shuffle || playable.size < 2) return playable
        return playable.shuffled(Random(cycleIndex * 31 + zone.key.hashCode()))
    }
}
