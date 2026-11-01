package dev.hivens.skinema.core

/**
 * Tear-free latest-value exchange between one producer and one consumer
 * (the decode thread and the render side). Three slots rotate roles:
 * the producer always owns a private slot to mutate, [publish] swaps it
 * with the pending slot, and [acquire] hands the consumer the freshest
 * published slot, which stays untouched until the next acquire. Neither
 * side ever mutates a slot the other holds, so big pixel buffers move
 * with zero copies and no torn reads. A slower consumer simply skips
 * intermediate frames -- exactly the drop-late policy from ROADMAP.md
 * section 6.
 */
class TripleBuffer<T>(slotA: T, slotB: T, slotC: T) {

    private val lock = Any()
    private var writeSlot = slotA
    private var pendingSlot = slotB
    private var readSlot = slotC
    private var fresh = false

    /** The producer's current scratch slot; mutate freely before [publish]. */
    var writing: T = writeSlot
        private set

    /** Publishes the scratch slot; [writing] becomes a new private slot. */
    fun publish() {
        synchronized(lock) {
            val freed = pendingSlot
            pendingSlot = writeSlot
            writeSlot = freed
            fresh = true
            writing = freed
        }
    }

    /**
     * The freshest published slot, or null when nothing new was published
     * since the previous acquire (keep using the last returned slot).
     */
    fun acquire(): T? = synchronized(lock) {
        if (!fresh) return null
        val freed = readSlot
        readSlot = pendingSlot
        pendingSlot = freed
        fresh = false
        readSlot
    }
}
