package dev.hivens.skinema.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioClockTest {

    private var frames = 0L
    private val clock = AudioClock(48_000) { frames }

    /**
     * Detaching to wall time is the hatch for a device that has stopped
     * answering, and reading the line is exactly what a caller cannot afford
     * then: a JavaSound line answers its position under the same native
     * monitor its blocking write holds, so the call parks for as long as the
     * write does. Every reader of this clock -- the pacer, the decode thread,
     * the subtitle thread, the consumer's render loop -- went in after it, so
     * a player whose device died wedged whole instead of degrading to
     * silence. Once detached, the line is not to be touched again.
     */
    @Test
    fun `a detached clock never asks the device again`() {
        var reads = 0
        var position = 0L
        val watched = AudioClock(48_000) { reads++; position }
        watched.start(0)
        position = 24_000
        watched.mediaNanos()

        watched.detachToWallTime(readDevice = false)
        val before = reads
        repeat(20) { watched.mediaNanos() }
        watched.pause()
        watched.resume()
        watched.seek(2_000_000_000L)
        watched.setTempo(2.0)
        // start() belongs in this list and was missing from it, which is how
        // it went on reading the line raw. It is reachable detached: the
        // player starts the clock when it owns it, and it owns it once the
        // audio side has gone -- which is what a device that stopped
        // answering causes in the first place.
        watched.start(3_000_000_000L)
        assertEquals(before, reads, "a detached clock read the line $reads times, was $before")

        // Re-attaching is the one call that must read it: it anchors on the
        // fresh line's own position.
        watched.rebase(2_000_000_000L, 48_000)
        assertTrue(reads > before, "the re-attach must read the fresh line")
    }

    @Test
    fun `media time is consumed samples over the rate`() {
        clock.start(0)
        frames = 48_000
        assertEquals(1_000_000_000L, clock.mediaNanos())
        frames = 60_000
        assertEquals(1_250_000_000L, clock.mediaNanos())
    }

    @Test
    fun `starts anchored at the given media position`() {
        frames = 1_000
        clock.start(5_000_000_000L)
        frames = 1_000 + 24_000
        assertEquals(5_500_000_000L, clock.mediaNanos())
    }

    @Test
    fun `a frozen position freezes media time -- pause by construction`() {
        clock.start(0)
        frames = 9_600
        val before = clock.mediaNanos()
        clock.pause()
        assertEquals(before, clock.mediaNanos(), "a stopped device stops time")
        assertTrue(clock.isPaused)
    }

    @Test
    fun `seek re-anchors at the current device position`() {
        clock.start(0)
        frames = 10_000
        clock.seek(2_000_000_000L)
        assertEquals(2_000_000_000L, clock.mediaNanos())
        frames = 10_000 + 4_800
        assertEquals(2_100_000_000L, clock.mediaNanos())
    }

    @Test
    fun `a backward device-position wobble does not run media time backward`() {
        clock.start(0)
        frames = 9_600
        assertEquals(200_000_000L, clock.mediaNanos())
        // Driver reconciliation glitch: the reported position dips.
        frames = 9_000
        assertEquals(200_000_000L, clock.mediaNanos(), "the wobble must be clamped, not shown")
        frames = 10_000
        assertEquals(208_333_333L, clock.mediaNanos(), "real progress resumes past the clamp")
    }

    @Test
    fun `a backward seek legitimately moves media time backward`() {
        clock.start(0)
        frames = 48_000
        assertEquals(1_000_000_000L, clock.mediaNanos())
        clock.seek(250_000_000L)
        assertEquals(250_000_000L, clock.mediaNanos(), "a re-anchor resets the monotonic floor")
    }

    @Test
    fun `rebase continues media time at the anchor and scales by the new rate`() {
        clock.start(0)
        frames = 48_000
        assertEquals(1_000_000_000L, clock.mediaNanos())
        // A track switch reopens the line: position restarts at zero and
        // the rate may change.
        frames = 0
        clock.rebase(1_000_000_000L, 96_000)
        assertEquals(1_000_000_000L, clock.mediaNanos(), "continuous at the anchor")
        frames = 96_000
        assertEquals(2_000_000_000L, clock.mediaNanos(), "deltas scale by the new rate")
    }

    @Test
    fun `tempo scales media advance per consumed frame`() {
        clock.start(0)
        clock.setTempo(2.0)
        frames = 4_800
        assertEquals(200_000_000L, clock.mediaNanos(), "tempo 2: 100ms of device frames covers 200ms of media")
    }

    @Test
    fun `a tempo change re-anchors -- the new scale applies only forward`() {
        clock.start(0)
        frames = 48_000
        assertEquals(1_000_000_000L, clock.mediaNanos())
        clock.setTempo(2.0)
        assertEquals(1_000_000_000L, clock.mediaNanos(), "continuous at the change")
        frames = 48_000 + 24_000
        assertEquals(2_000_000_000L, clock.mediaNanos(), "history unscaled, the future at 2x")
    }

    @Test
    fun `tempo survives a seek re-anchor`() {
        clock.start(0)
        clock.setTempo(0.5)
        frames = 10_000
        clock.seek(3_000_000_000L)
        frames = 10_000 + 48_000
        assertEquals(3_500_000_000L, clock.mediaNanos(), "rate persists across seeks")
    }

    /**
     * A device answers about its position once per period and says nothing in
     * between -- measured on this machine as 21.3 ms of stillness followed by
     * a 21.3 ms jump. Media time read straight off it is a staircase, and
     * video paced on a staircase gets its frames due in bursts: 60 fps
     * content delivered 48.4 distinct frames a second to a consumer, the rest
     * overwritten in the mailbox before anything could take them.
     *
     * The four tests below fix the terms of the fix. The wall clock fills the
     * gaps, and what bounds it is the device's own last step: past one period
     * of silence the reading is no longer evidence that anything is playing.
     */
    @Test
    fun `media time fills the gap between the device's position refreshes`() {
        clock.start(0)
        // Two refreshes are what it takes to know the cadence: 480 frames,
        // 10 ms apart at this rate.
        frames = 480
        clock.mediaNanos()
        frames = 960
        val atRefresh = clock.mediaNanos()
        assertEquals(20_000_000L, atRefresh)

        Thread.sleep(5)
        val between = clock.mediaNanos()
        assertTrue(between > atRefresh, "media time stood still between two refreshes")
        assertTrue(
            between <= atRefresh + 10_000_000L,
            "and it must not outrun the device's own step, gained ${between - atRefresh}ns",
        )
    }

    @Test
    fun `the gap fill stops at one device period`() {
        clock.start(0)
        frames = 480
        clock.mediaNanos()
        frames = 960
        val atRefresh = clock.mediaNanos()

        // Twenty periods of silence. A device that has stopped answering is
        // an underrun, a stopped line or a dead one, and holding is the right
        // answer to all three.
        Thread.sleep(200)
        assertEquals(
            atRefresh + 10_000_000L,
            clock.mediaNanos(),
            "a device that stopped answering must not keep media time running",
        )
    }

    @Test
    fun `the gap fill has a ceiling no device period may raise`() {
        clock.start(0)
        // A "device" that answers once a second -- past anything a real line
        // does, so the step alone is no longer a safe bound.
        frames = 48_000
        clock.mediaNanos()
        frames = 96_000
        val atRefresh = clock.mediaNanos()

        Thread.sleep(200)
        assertEquals(
            atRefresh + 60_000_000L,
            clock.mediaNanos(),
            "the ceiling, not the step, has to bound an implausible period",
        )
    }

    /**
     * The pipeline stops the line for seeks, track switches and rate changes,
     * and holds it stopped until the picture lands -- seconds, on a sparse
     * keyframe run. It reads the playhead in that window to re-anchor on, so
     * time that crept forward there is time the re-anchor then takes back:
     * the one move the video side's invariants forbid.
     */
    @Test
    fun `a stopped line stops the gap fill`() {
        clock.start(0)
        frames = 480
        clock.mediaNanos()
        frames = 960
        val atRefresh = clock.mediaNanos()

        clock.setDeviceRunning(false)
        Thread.sleep(30)
        assertEquals(atRefresh, clock.mediaNanos(), "a line that is not consuming plays nothing to count")
    }

    @Test
    fun `restarting the line does not credit the stop as time the device played`() {
        clock.start(0)
        frames = 480
        clock.mediaNanos()
        frames = 960
        val atRefresh = clock.mediaNanos()

        clock.setDeviceRunning(false)
        Thread.sleep(50)
        clock.setDeviceRunning(true)
        val gained = clock.mediaNanos() - atRefresh
        assertTrue(
            gained < 5_000_000L,
            "the stop is not a period the device spent playing, gained ${gained}ns",
        )
    }

    /**
     * The device is asked OUTSIDE the lock -- there is no reason to serialise
     * every reader behind the slowest one -- and the answer is applied under
     * the lock, against an anchor that may have moved in between. A track switch and a device-loss recovery both reopen
     * the line, so its counter restarts at zero while a reading already in
     * flight still carries the old one's total.
     *
     * Applied against the fresh anchor that is a leap of the whole elapsed
     * playing time, and [AudioClock.mediaNanos] writes what it returns into
     * its monotonic floor -- so the leap is permanent, not transient: every
     * honest reading afterwards sits below the floor and the clock never
     * comes back. Video then has nothing due until real media time climbs
     * past it, which is the elapsed play time all over again.
     */
    @Test
    fun `a device reading taken before a re-anchor is not applied after it`() {
        val position = java.util.concurrent.atomic.AtomicLong(0)
        val reader = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
        val sampled = java.util.concurrent.CountDownLatch(1)
        val rebased = java.util.concurrent.CountDownLatch(1)
        // The reading is captured and then parked, which is exactly the
        // window between the sample and the lock.
        val watched = AudioClock(48_000) {
            val taken = position.get()
            if (Thread.currentThread() === reader.get()) {
                sampled.countDown()
                rebased.await()
            }
            taken
        }

        watched.start(0)
        position.set(48_000L * 60)
        assertEquals(60_000_000_000L, watched.mediaNanos(), "a minute of playback on the current line")

        var seen = -1L
        val thread = Thread { seen = watched.mediaNanos() }
        reader.set(thread)
        thread.start()
        assertTrue(sampled.await(5, java.util.concurrent.TimeUnit.SECONDS), "the reader must take its sample")

        // The audio thread reopens the line and re-anchors on it: a fresh
        // line counts from zero.
        position.set(0)
        watched.rebase(60_000_000_000L, 48_000)
        rebased.countDown()
        thread.join(5_000)

        assertTrue(
            seen in 59_000_000_000L..61_000_000_000L,
            "the stale reading was applied to the new anchor and read ${seen / 1_000_000}ms",
        )
        assertTrue(
            watched.mediaNanos() in 59_000_000_000L..61_000_000_000L,
            "and it latched the floor at ${watched.mediaNanos() / 1_000_000}ms, where the clock now stays",
        )
    }

    /**
     * Readings are sampled outside the lock by five threads, and taking the
     * lock does not preserve the order they sampled in -- so an older reading
     * can land after a newer one. It arrives carrying a FRESH wall time,
     * because that is read after the sample returns, which is why nothing
     * about the clock distinguishes it and only the count can.
     *
     * Accepted, it walks the cadence anchor backward, and the next honest
     * reading measures its step from there: several periods where there was
     * one. That step is the bound on how long the gap fill may run with the
     * device saying nothing, so a corrupted one lets the wall clock invent
     * several periods of media time on a device that has stalled.
     */
    @Test
    fun `a reading that lands after a newer one does not widen the cadence`() {
        clock.start(0)
        // Three refreshes, 480 frames apart: one period is 10 ms here.
        frames = 480
        clock.mediaNanos()
        frames = 960
        clock.mediaNanos()
        frames = 1_440
        clock.mediaNanos()

        // The straggler: sampled before the 1440 reading, landing after it.
        frames = 960
        clock.mediaNanos()

        frames = 1_920
        val atRefresh = clock.mediaNanos()
        Thread.sleep(200)
        assertEquals(
            atRefresh + 10_000_000L,
            clock.mediaNanos(),
            "the straggler widened the step, so the fill ran for more than the one period it may",
        )
    }

    @Test
    fun `detachToWallTime keeps time moving without the device`() {
        clock.start(0)
        frames = 48_000
        clock.detachToWallTime()
        val atDetach = clock.mediaNanos()
        assertTrue(atDetach >= 1_000_000_000L)
        Thread.sleep(50)
        assertTrue(
            clock.mediaNanos() >= atDetach + 30_000_000L,
            "detached time must advance on the wall clock even with the device frozen",
        )
    }

    /**
     * pause() is the interface's promise to freeze media time, and detached
     * from the device it is the only thing that can: there is no frame
     * position to stop. Without it a finished player kept counting past the
     * end of its own file, without limit.
     */
    @Test
    fun `pause freezes detached time, and resume picks it back up`() {
        clock.start(0)
        frames = 48_000
        clock.detachToWallTime()
        Thread.sleep(30)
        clock.pause()
        val frozen = clock.mediaNanos()
        Thread.sleep(50)
        assertEquals(frozen, clock.mediaNanos(), "paused time must not move, device or no device")

        clock.resume()
        Thread.sleep(50)
        assertTrue(clock.mediaNanos() >= frozen + 30_000_000L, "resume returns to the wall clock")
    }

    @Test
    fun `a seek is honoured while detached`() {
        clock.start(0)
        frames = 48_000
        clock.detachToWallTime()
        clock.pause()
        clock.seek(7_000_000_000L)
        assertEquals(7_000_000_000L, clock.mediaNanos(), "a seek must move detached time too")
    }
}
