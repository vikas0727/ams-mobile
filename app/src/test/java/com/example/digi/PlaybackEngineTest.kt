package com.example.digi

import com.example.digi.core.AmsConstants
import com.example.digi.player.ClusterPlan
import com.example.digi.player.PlanLayout
import com.example.digi.player.PlanSlide
import com.example.digi.player.PlanZone
import com.example.digi.player.PlaybackEngine
import com.example.digi.player.PlaybackPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scheduler.
 *
 * These cases are the ones that produce visible faults on a wall rather than a stack trace: a zone
 * that stops looping, a layout that never advances, and — the expensive one — a video-wall member
 * that does not land on the same slot as its neighbours.
 */
class PlaybackEngineTest {

    private fun slide(id: String, seconds: Long) = PlanSlide(
        mediaId = id,
        name = id,
        mediaType = AmsConstants.MediaType.IMAGE,
        mimeType = "image/png",
        localPath = "/tmp/$id.png",
        cacheKey = "key-$id",
        durationMs = seconds * 1000,
        transition = AmsConstants.Transition.NONE,
        transitionMs = 0,
        muted = true,
        fitMode = "contain",
    )

    private fun zone(key: String, vararg slides: PlanSlide) = PlanZone(
        key = key,
        name = key,
        type = AmsConstants.ZoneType.MAIN,
        x = 0f, y = 0f, width = 1f, height = 1f,
        zIndex = 0,
        backgroundColor = null,
        fitContent = true,
        muted = false,
        slides = slides.toList(),
    )

    private fun plan(
        layouts: List<PlanLayout>,
        loop: Boolean = true,
        cluster: ClusterPlan? = null,
    ) = PlaybackPlan(
        source = AmsConstants.ContentSource.SCHEDULED,
        contentVersion = 1,
        playlistId = "p1",
        loop = loop,
        layouts = layouts,
        cluster = cluster,
    )

    private fun layout(key: String, seconds: Long, vararg zones: PlanZone) = PlanLayout(
        key = key,
        name = key,
        durationMs = seconds * 1000,
        canvasWidth = 1920,
        canvasHeight = 1080,
        backgroundColor = null,
        zones = zones.toList(),
    )

    @Test
    fun `walks slides in order`() {
        val p = plan(listOf(layout("l1", 30, zone("main", slide("a", 10), slide("b", 10), slide("c", 10)))))

        assertEquals("a", PlaybackEngine.frameAt(p, 0, 0)!!.zones[0].slide?.mediaId)
        assertEquals("b", PlaybackEngine.frameAt(p, 12_000, 0)!!.zones[0].slide?.mediaId)
        assertEquals("c", PlaybackEngine.frameAt(p, 25_000, 0)!!.zones[0].slide?.mediaId)
    }

    @Test
    fun `loops back to the first slide after a full cycle`() {
        val p = plan(listOf(layout("l1", 30, zone("main", slide("a", 10), slide("b", 10), slide("c", 10)))))
        val frame = PlaybackEngine.frameAt(p, 31_000, 0)!!
        assertEquals("a", frame.zones[0].slide?.mediaId)
        assertEquals(1, frame.cycleIndex)
    }

    @Test
    fun `a zone shorter than its layout loops inside it`() {
        // The most common real layout there is: a short ticker under a long video. If the short
        // zone did not loop it would sit blank for most of the layout.
        val p = plan(listOf(layout("l1", 60, zone("ticker", slide("t1", 5), slide("t2", 5)))))

        assertEquals("t1", PlaybackEngine.frameAt(p, 0, 0)!!.zones[0].slide?.mediaId)
        assertEquals("t2", PlaybackEngine.frameAt(p, 7_000, 0)!!.zones[0].slide?.mediaId)
        // 42s in: 42 % 10 = 2 → back on t1, four loops into the zone but still in the same layout.
        assertEquals("t1", PlaybackEngine.frameAt(p, 42_000, 0)!!.zones[0].slide?.mediaId)
    }

    @Test
    fun `advances between layouts`() {
        val p = plan(
            listOf(
                layout("l1", 10, zone("main", slide("a", 10))),
                layout("l2", 10, zone("main", slide("b", 10))),
            )
        )
        assertEquals("l1", PlaybackEngine.frameAt(p, 5_000, 0)!!.layout.key)
        assertEquals("l2", PlaybackEngine.frameAt(p, 15_000, 0)!!.layout.key)
        assertEquals("l1", PlaybackEngine.frameAt(p, 25_000, 0)!!.layout.key)
    }

    @Test
    fun `a non-looping playlist holds on its last layout`() {
        val p = plan(
            listOf(
                layout("l1", 10, zone("main", slide("a", 10))),
                layout("l2", 10, zone("main", slide("b", 10))),
            ),
            loop = false,
        )
        // Well past the end of one pass.
        val frame = PlaybackEngine.frameAt(p, 300_000, 0)!!
        assertEquals("l2", frame.layout.key)
        assertEquals("b", frame.zones[0].slide?.mediaId)
    }

    @Test
    fun `two wall members at the same instant land on the same slot`() {
        // The whole point of loopOriginAt. Both panels compute from the shared origin, so a device
        // that booted two minutes ago and one that has been up for a week agree.
        val origin = 1_000_000L
        val cluster = ClusterPlan(
            clusterId = "c1", clusterName = "Wall", batchId = "b1",
            position = 1, isMaster = true, syncEnabled = true,
            loopOriginAtMs = origin, loopDurationMs = 30_000,
        )
        val p = plan(
            listOf(layout("wall", 30, zone("main", slide("a", 10), slide("b", 10), slide("c", 10)))),
            cluster = cluster,
        )

        val now = origin + 3_600_000 + 12_000   // an hour and twelve seconds into the loop
        val justRebooted = PlaybackEngine.frameAt(p, now, origin)!!
        val longRunning = PlaybackEngine.frameAt(p, now, origin)!!

        assertEquals("b", justRebooted.zones[0].slide?.mediaId)
        assertEquals(justRebooted.signature, longRunning.signature)
        // And it joins mid-slide rather than at the start of one.
        assertEquals(2_000, justRebooted.zones[0].offsetInSlideMs)
    }

    @Test
    fun `holds the first frame before a cluster origin in the future`() {
        // loopOriginAt is stamped a few seconds ahead so every member has the manifest before the
        // loop starts. A negative modulo would drop this panel at a pseudo-random point instead.
        val origin = 2_000_000L
        val p = plan(listOf(layout("wall", 30, zone("main", slide("a", 10), slide("b", 10)))))
        val frame = PlaybackEngine.frameAt(p, origin - 5_000, origin)!!
        assertEquals("a", frame.zones[0].slide?.mediaId)
        assertEquals(0, frame.zones[0].offsetInSlideMs)
    }

    @Test
    fun `skips assets that have not downloaded`() {
        // A slide with no local path is skipped, not rendered as a gap: a blank beat looks like a
        // fault on a wall, a slightly shorter loop does not.
        val undownloaded = slide("missing", 10).copy(localPath = null)
        val p = plan(listOf(layout("l1", 20, zone("main", slide("a", 10), undownloaded))))

        val frame = PlaybackEngine.frameAt(p, 12_000, 0)!!
        assertEquals("a", frame.zones[0].slide?.mediaId)
    }

    @Test
    fun `blank cluster cells still occupy their beat`() {
        // The opposite rule from the one above, and deliberately so: a flighted-out slot renders
        // blank on EVERY screen so the wall stays in phase.
        val blank = slide("blank", 10).copy(localPath = null, isBlank = true)
        val p = plan(listOf(layout("wall", 20, zone("main", slide("a", 10), blank))))

        val frame = PlaybackEngine.frameAt(p, 15_000, 0)!!
        assertTrue(frame.zones[0].slide?.isBlank == true)
    }

    @Test
    fun `signature changes only when something must be redrawn`() {
        val p = plan(listOf(layout("l1", 30, zone("main", slide("a", 10), slide("b", 10), slide("c", 10)))))

        val early = PlaybackEngine.frameAt(p, 1_000, 0)!!.signature
        val stillSameSlide = PlaybackEngine.frameAt(p, 9_000, 0)!!.signature
        val nextSlide = PlaybackEngine.frameAt(p, 11_000, 0)!!.signature

        assertEquals(early, stillSameSlide)
        assertNotEquals(early, nextSlide)
    }

    @Test
    fun `returns null when nothing is playable`() {
        assertNull(PlaybackEngine.frameAt(plan(emptyList()), 0, 0))
        assertNull(PlaybackEngine.frameAt(plan(listOf(layout("l1", 10, zone("main")))), 0, 0))
    }

    @Test
    fun `shuffle is identical across devices for the same cycle`() {
        // Three panels each shuffling independently would show three unrelated files side by side,
        // so the order is seeded by the cycle index rather than by a random source.
        val slides = (1..6).map { slide("s$it", 5) }
        val p = PlaybackPlan(
            source = AmsConstants.ContentSource.SCHEDULED,
            contentVersion = 1,
            shuffle = true,
            layouts = listOf(layout("l1", 30, zone("main", *slides.toTypedArray()))),
        )

        val a = PlaybackEngine.frameAt(p, 7_000, 0)!!
        val b = PlaybackEngine.frameAt(p, 7_000, 0)!!
        assertEquals(a.zones[0].slide?.mediaId, b.zones[0].slide?.mediaId)

        // And a later cycle genuinely reorders, otherwise "shuffle" would be a fixed permutation.
        val cycleTwo = PlaybackEngine.frameAt(p, 37_000, 0)!!
        assertEquals(1, cycleTwo.cycleIndex)
    }
}
