package dev.hivens.skinema.libav

/**
 * A byte source skinema reads from instead of a file: the seam for
 * streams. A streaming companion fetches bytes (HLS/DASH segments, a
 * download, memory) and hands them over here; skinema never does the I/O
 * itself, so the `--disable-network` guarantee holds (the bundled FFmpeg
 * cannot reach the network, it only pulls bytes through these callbacks).
 *
 * Hand one to a decoder directly, or to the player and the transcoder as
 * [MediaInput.Stream].
 *
 * **Seekable or forward only.** A source at its start that answers
 * `seek(0)` with 0 can seek, and is read like a file: the player's picture,
 * sound and subtitles each read it through a cursor of their own, so it is
 * asked for different offsets in turn, and track switches, subtitles turned
 * on mid-play, seeks and steps all work. Keep [seek] cheap (a cache in
 * front of a network source) or answer -1. A source answering -1 reads
 * forward only, the shape of a live stream: it is read once, by one demuxer
 * every side shares, and the player then reports
 * [dev.hivens.skinema.player.VideoPlayer.seekable] false.
 *
 * **Threads.** Calls into one source never overlap, but they may come from
 * more than one thread over its life (the cursors of a seekable source, a
 * shared demuxer's own thread), so it must not rely on thread-local state.
 *
 * **Ownership.** Whatever it was handed to owns it from then on and calls
 * [close] when done: a decoder at its own close, the player when it moves
 * off the stream or closes, the transcoder at its close. [close] may be
 * called while a [read] is blocked on another thread, and it must make that
 * read return (or throw): that is how a player tears down a stalled stream
 * within its close budget.
 */
interface MediaSource : AutoCloseable {

    /**
     * Reads up to [length] bytes into [dst] starting at [offset]; returns
     * the number of bytes read, or -1 at end of stream. Must not return 0
     * (block until at least one byte is available, or report the end), and
     * must not return more than [length]. Throwing fails the reader closed,
     * with the throwable as the cause.
     */
    fun read(dst: ByteArray, offset: Int, length: Int): Int

    /**
     * Repositions to absolute byte [position] and returns the new position,
     * or -1 when the source cannot seek. A forward-only stream always
     * answers -1.
     */
    fun seek(position: Long): Long = -1

    /** Total size in bytes, or -1 when unknown (live / unbounded stream). */
    fun size(): Long = -1

    override fun close() {}
}
