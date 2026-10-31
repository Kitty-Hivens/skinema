package dev.hivens.skinema.player

import dev.hivens.skinema.libav.DeviceFixtures
import dev.hivens.skinema.subtitles.SubtitleOverlay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SubtitleDeviceTest {

    private val players = mutableListOf<VideoPlayer>()

    @AfterTest
    fun closeAll() = players.forEach { it.close() }

    private fun player(name: String, audio: Boolean = false) =
        VideoPlayer(DeviceFixtures.path(name), loop = false, audio = audio).also { players += it }

    private fun awaitTrue(timeoutMillis: Long = 5_000, what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun awaitOverlay(p: VideoPlayer, timeoutMillis: Long = 5_000, accept: (SubtitleOverlay) -> Boolean): SubtitleOverlay {
        var got: SubtitleOverlay? = null
        awaitTrue(timeoutMillis, "an overlay") {
            p.acquireSubtitles()?.takeIf(accept)?.also { got = it } != null
        }
        return got!!
    }

    /** Whether the overlay actually carries ink: some pixel with alpha. */
    private fun inked(o: SubtitleOverlay): Boolean =
        o.patches.any { patch -> (3 until patch.rgba.size step 4).any { patch.rgba[it].toInt() and 0xFF > 128 } }

    @Test
    fun anEmbeddedTrackIsListedSelectedAndDrawn() {
        val p = player("subs.mkv")
        awaitTrue(what = "the track list") { p.subtitleTracks.isNotEmpty() }
        val track = p.subtitleTracks.single()
        assertEquals("eng", track.language)
        assertTrue(track.isText)
        assertNull(p.activeSubtitleTrack)
        p.selectSubtitleTrack(track.id)
        awaitTrue(what = "the selection") { p.activeSubtitleTrack == track.id }
        val shown = awaitOverlay(p) { inked(it) }
        assertEquals(320, shown.canvasWidth)
        assertEquals(240, shown.canvasHeight)
    }

    @Test
    fun theGapBetweenCuesIsAClearAndTheCanvasFollowsTheAnnouncement() {
        val p = player("subs.mkv")
        awaitTrue(what = "the track list") { p.subtitleTracks.isNotEmpty() }
        p.setSubtitleCanvasSize(800, 600)
        p.selectSubtitleTrack(p.subtitleTracks.single().id)
        val first = awaitOverlay(p) { inked(it) }
        assertEquals(800, first.canvasWidth)
        assertEquals(600, first.canvasHeight)
        awaitOverlay(p, 3_000) { it.patches.isEmpty() }
        val second = awaitOverlay(p, 3_000) { inked(it) }
        assertTrue(second.generation > first.generation)
    }

    @Test
    fun deselectingTurnsSubtitlesOffAndClears() {
        val p = player("subs.mkv")
        awaitTrue(what = "the track list") { p.subtitleTracks.isNotEmpty() }
        p.selectSubtitleTrack(p.subtitleTracks.single().id)
        awaitOverlay(p) { inked(it) }
        p.selectSubtitleTrack(null)
        awaitTrue(what = "off") { p.activeSubtitleTrack == null }
        awaitOverlay(p) { it.patches.isEmpty() }
    }

    @Test
    fun aSideLoadedFileTakesANegativeIdAndIsDrawn() {
        val p = player("long-with-sound.mp4")
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing }
        val added = p.addExternalSubtitles(DeviceFixtures.path("subs.srt"))
        assertEquals(1, added.size)
        val track = added.single()
        assertTrue(track.id < 0)
        assertEquals(track.id, p.subtitleTracks.last().id)
        p.selectSubtitleTrack(track.id)
        awaitTrue(what = "the selection") { p.activeSubtitleTrack == track.id }
        awaitOverlay(p, 6_000) { inked(it) }
    }

    @Test
    fun movTextInMp4IsListedAndDrawn() {
        val p = player("subs-movtext.mp4")
        awaitTrue(what = "the track list") { p.subtitleTracks.isNotEmpty() }
        p.selectSubtitleTrack(p.subtitleTracks.single().id)
        awaitOverlay(p) { inked(it) }
    }

    @Test
    fun aPlayerWithNoPictureTakesNoSubtitles() {
        val p = player("sine-aac.m4a", audio = true)
        awaitTrue(what = "Playing") { p.state == VideoPlayer.State.Playing || p.state == VideoPlayer.State.Ended }
        assertTrue(p.addExternalSubtitles(DeviceFixtures.path("subs.srt")).isEmpty())
        assertTrue(p.subtitleTracks.isEmpty())
    }
}
