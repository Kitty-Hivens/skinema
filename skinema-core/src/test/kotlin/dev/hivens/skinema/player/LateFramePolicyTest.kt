package dev.hivens.skinema.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LateFramePolicyTest {

    @Test
    fun `an on-time frame publishes`() {
        assertTrue(shouldPublishLateFrame(lateNanos = 0, sincePublishNanos = 0))
    }

    @Test
    fun `pacing slack within the drop threshold publishes`() {
        assertTrue(shouldPublishLateFrame(lateNanos = CHASE_DROP_NANOS, sincePublishNanos = 0))
    }

    @Test
    fun `a catch-up frame drops without converting`() {
        assertFalse(shouldPublishLateFrame(lateNanos = CHASE_DROP_NANOS + 1, sincePublishNanos = 0))
        assertFalse(shouldPublishLateFrame(lateNanos = 10_000_000_000L, sincePublishNanos = CHASE_PUBLISH_INTERVAL_NANOS - 1))
    }

    @Test
    fun `the starvation guard surfaces one frame per interval`() {
        assertTrue(shouldPublishLateFrame(lateNanos = 10_000_000_000L, sincePublishNanos = CHASE_PUBLISH_INTERVAL_NANOS))
    }
}
