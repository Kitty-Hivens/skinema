package dev.hivens.skinema.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule that decides whether a window is in front of anyone, on times
 * handed to it rather than on a window. The timings come from the machine
 * the rule was measured on: a window on screen answers within a refresh, and
 * a hidden XWayland window answers about once a second.
 */
class SightTrackerTest {

    private val ms = 1_000_000L

    /** One request answered [latency] after it was asked, at [at]. Returns when it was answered. */
    private fun SightTracker.frame(at: Long, latency: Long): Long {
        asked(at)
        answered(at + latency)
        return at + latency
    }

    @Test
    fun `a window answering within a refresh stays in sight`() {
        val sight = SightTracker()
        var t = 0L
        repeat(600) { t = sight.frame(t, 5 * ms) + 28 * ms }
        assertTrue(sight.inSight)
    }

    /** The measured case: an unseen workspace draws once a second, and the requests go back to back. */
    @Test
    fun `two seconds of once-a-second answers is a hidden window`() {
        val sight = SightTracker()
        var t = sight.frame(0L, 1_000 * ms)
        assertTrue(sight.inSight, "one slow answer is a hiccup")
        t = sight.frame(t + 5 * ms, 1_000 * ms)
        assertFalse(sight.inSight, "slow answers without a break for two seconds")
    }

    /**
     * What a hidden XWayland window actually does, as measured: the answer
     * takes a second, and the next request goes out a second after it,
     * because the thread that answers sits in the buffer swap for that long.
     * A second between requests is not an idle surface.
     */
    @Test
    fun `a request a second after a slow answer continues the count`() {
        val sight = SightTracker()
        var t = sight.frame(0L, 1_000 * ms)
        t = sight.frame(t + 1_000 * ms, 1_000 * ms)
        assertFalse(sight.inSight, "answers of a second, a second apart, for three seconds")
    }

    @Test
    fun `a quick answer in between starts the count again`() {
        val sight = SightTracker()
        var t = sight.frame(0L, 1_500 * ms)
        t = sight.frame(t, 5 * ms)
        t = sight.frame(t, 1_500 * ms)
        assertTrue(sight.inSight, "the slowness was not continuous")
    }

    /**
     * A paused player asks for nothing. The hiccup before the pause and the
     * one after it are not two seconds of a hidden window.
     */
    @Test
    fun `an idle stretch between requests starts the count again`() {
        val sight = SightTracker()
        var t = sight.frame(0L, 1_500 * ms)
        t += 10_000 * ms
        sight.frame(t, 1_500 * ms)
        assertTrue(sight.inSight)
    }

    /** A frame clock that stopped altogether never answers, so the waiting request is what gets judged. */
    @Test
    fun `a request nobody answers is judged while it waits`() {
        val sight = SightTracker()
        sight.asked(0L)
        sight.check(1_000 * ms)
        assertTrue(sight.inSight)
        sight.check(2_000 * ms)
        assertFalse(sight.inSight)
    }

    @Test
    fun `three quick answers in a row bring a hidden window back`() {
        val sight = SightTracker()
        var t = sight.frame(0L, 1_000 * ms)
        t = sight.frame(t, 1_100 * ms)
        assertFalse(sight.inSight)
        t = sight.frame(t, 5 * ms)
        t = sight.frame(t, 5 * ms)
        assertFalse(sight.inSight, "a hidden window can answer quickly now and then")
        t = sight.frame(t, 1_000 * ms)
        t = sight.frame(t, 5 * ms)
        t = sight.frame(t, 5 * ms)
        assertFalse(sight.inSight, "the run was broken")
        sight.frame(t, 5 * ms)
        assertTrue(sight.inSight)
    }

    /** Two requesters waiting on the same frame: the earlier one is what the answer is measured from. */
    @Test
    fun `a second request while one waits keeps the earlier time`() {
        val sight = SightTracker()
        sight.asked(0L)
        sight.asked(900 * ms)
        sight.answered(1_000 * ms)
        sight.asked(1_000 * ms)
        sight.asked(1_900 * ms)
        sight.answered(2_000 * ms)
        assertFalse(sight.inSight)
    }

    @Test
    fun `the verdict is only news once, and a withdrawal makes it news again`() {
        val sight = SightTracker()
        assertEquals(true, sight.verdict(started = true))
        assertNull(sight.verdict(started = true))
        assertEquals(false, sight.verdict(started = false), "a minimised window is out of sight")
        assertNull(sight.verdict(started = false))
        sight.withdrawn()
        assertEquals(false, sight.verdict(started = false))
    }
}
