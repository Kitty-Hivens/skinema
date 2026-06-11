package dev.hivens.skinema.player

/**
 * Decoded-ahead inventory between the decode thread and the pacer thread:
 * a fixed ring of cells, each owning a reusable RGBA array. The producer
 * converts into [writeCell] and [commit]s; the consumer peeks the head's
 * timing and [poll]s with a replacement array -- the cell's filled array
 * and the replacement swap owners, so frames cross the seam without a
 * copy and the arrays keep cycling between the queue and the mailbox.
 *
 * Single producer, single consumer; the producer's writeCell/commit/clear
 * must all run on one thread. The lock is held only for pointer turns --
 * pixel work happens outside it. A cleared cell can never surface: [poll]
 * hands out only what is committed at that instant, and the consumer
 * never holds a peeked cell across a wait.
 */
internal class FrameQueue(depth: Int) {

    internal class Cell {
        var rgba = ByteArray(0)
        var width = 0
        var height = 0
        var ptsNanos = 0L
    }

    /** A polled frame; the array's ownership moved to the caller. */
    internal class Frame(
        val rgba: ByteArray,
        val width: Int,
        val height: Int,
        val ptsNanos: Long,
        val forced: Boolean,
    )

    /** Head snapshot for pace decisions; taken in one lock round. */
    internal class Head(
        val ptsNanos: Long,
        val forced: Boolean,
        val byteCount: Int,
    )

    private val lock = Object()
    private val cells = Array(depth) { Cell() }
    private val forcedFlags = BooleanArray(depth)
    private var head = 0
    private var count = 0
    private var closed = false
    private var flushes = 0
    private var changes = 0L

    // -- Producer (decode thread) ---------------------------------------------

    val hasRoom: Boolean get() = synchronized(lock) { count < cells.size }

    /**
     * The next free cell. Resize its array and convert into it, then
     * [commit]. Stable across concurrent [poll]s: popping advances head
     * and shrinks count together, so the write index never moves.
     */
    fun writeCell(): Cell = synchronized(lock) {
        check(count < cells.size) { "no free cell; check hasRoom first" }
        cells[(head + count) % cells.size]
    }

    /**
     * Publishes the [writeCell] cell to the consumer. [forced] frames
     * bypass the pacer's state gate and late policy -- seek landings
     * (shown even mid-Paused) and chase guards (the fill side already
     * made the policy call; deciding twice double-drops).
     */
    fun commit(forced: Boolean = false) {
        synchronized(lock) {
            check(count < cells.size) { "commit without a writeCell" }
            forcedFlags[(head + count) % cells.size] = forced
            count++
            changes++
            lock.notifyAll()
        }
    }

    /**
     * Drops every committed frame (seek flush). Producer-side only.
     * Wakes the consumer: a pacer sleeping out a stale head's wait must
     * notice the flush -- the landing that follows publishes immediately.
     */
    fun clear() {
        synchronized(lock) {
            count = 0
            flushes++
            changes++
            lock.notifyAll()
        }
    }

    /** Wakes and permanently releases the consumer. */
    fun close() {
        synchronized(lock) {
            closed = true
            changes++
            lock.notifyAll()
        }
    }

    // -- Consumer (pacer thread) ----------------------------------------------

    val isEmpty: Boolean get() = synchronized(lock) { count == 0 }
    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** Bumped by [clear]; lets the consumer reset clock-jump tracking. */
    val flushCount: Int get() = synchronized(lock) { flushes }

    fun peekHead(): Head? = synchronized(lock) {
        if (count == 0) return null
        val cell = cells[head]
        Head(cell.ptsNanos, forcedFlags[head], cell.rgba.size)
    }

    /**
     * The mutation counter: read it before a peek, then sleep with
     * [awaitChange] -- any commit/clear/close since that reading returns
     * immediately instead of sleeping out a stale timeout. This is the
     * pacer's wake-up seam; an uninterruptible sleep here puts its full
     * length onto every seek landing.
     */
    fun changeTick(): Long = synchronized(lock) { changes }

    /** Blocks until the queue mutates past [sinceTick], or the timeout. */
    fun awaitChange(sinceTick: Long, timeoutNanos: Long) {
        synchronized(lock) {
            if (changes != sinceTick || closed) return
            lock.wait(timeoutNanos / 1_000_000L, (timeoutNanos % 1_000_000L).toInt())
        }
    }

    /**
     * Pops the head: the caller takes its array, the cell takes
     * [replacement] as future storage. Null when a concurrent [clear]
     * emptied the queue since the peek -- re-loop, never publish stale.
     */
    fun poll(replacement: ByteArray): Frame? = synchronized(lock) {
        if (count == 0) return null
        val cell = cells[head]
        val out = Frame(cell.rgba, cell.width, cell.height, cell.ptsNanos, forcedFlags[head])
        cell.rgba = replacement
        head = (head + 1) % cells.size
        count--
        out
    }

    /** Pops the head without taking its pixels (a dropped late frame). */
    fun dropHead() {
        synchronized(lock) {
            if (count == 0) return
            head = (head + 1) % cells.size
            count--
        }
    }
}
