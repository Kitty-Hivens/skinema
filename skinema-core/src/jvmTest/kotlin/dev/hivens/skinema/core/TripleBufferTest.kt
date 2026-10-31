package dev.hivens.skinema.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class TripleBufferTest {

    private class Slot(var value: Int = 0)

    @Test
    fun `acquire returns null until something is published`() {
        val buf = TripleBuffer(Slot(), Slot(), Slot())
        assertNull(buf.acquire())
    }

    @Test
    fun `consumer sees the published value`() {
        val buf = TripleBuffer(Slot(), Slot(), Slot())
        buf.writing.value = 42
        buf.publish()
        assertEquals(42, buf.acquire()?.value)
    }

    @Test
    fun `second acquire without a new publish returns null`() {
        val buf = TripleBuffer(Slot(), Slot(), Slot())
        buf.writing.value = 1
        buf.publish()
        buf.acquire()
        assertNull(buf.acquire(), "no new data -- consumer keeps the previous slot")
    }

    @Test
    fun `a slow consumer gets only the latest of several publishes`() {
        val buf = TripleBuffer(Slot(), Slot(), Slot())
        for (v in 1..5) {
            buf.writing.value = v
            buf.publish()
        }
        assertEquals(5, buf.acquire()?.value, "intermediate frames are dropped, not queued")
    }

    @Test
    fun `producer never receives the slot the consumer is holding`() {
        val buf = TripleBuffer(Slot(), Slot(), Slot())
        buf.writing.value = 1
        buf.publish()
        val held = buf.acquire()!!
        // Producer keeps publishing while the consumer holds `held`; none of
        // the scratch slots it gets may alias the held one.
        repeat(4) {
            assertNotSame(held, buf.writing, "consumer-held slot must never be handed to the producer")
            buf.writing.value = 100 + it
            buf.publish()
        }
        assertEquals(103, buf.acquire()?.value)
    }

    @Test
    fun `publish hands back a different scratch slot`() {
        val buf = TripleBuffer(Slot(), Slot(), Slot())
        val before = buf.writing
        buf.publish()
        assertNotSame(before, buf.writing)
    }

    @Test
    fun `consumer keeps the same slot instance until the next acquire`() {
        val buf = TripleBuffer(Slot(), Slot(), Slot())
        buf.writing.value = 7
        buf.publish()
        val first = buf.acquire()
        buf.writing.value = 8
        buf.publish()
        val second = buf.acquire()
        assertNotSame(first, second)
        assertEquals(7, first!!.value)
        assertEquals(8, second!!.value)
    }
}
