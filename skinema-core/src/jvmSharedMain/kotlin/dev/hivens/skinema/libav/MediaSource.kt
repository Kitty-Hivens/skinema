package dev.hivens.skinema.libav

/**
 * A byte source skinema decodes from instead of a file [java.nio.file.Path]
 * -- the seam for feeding segments or streams. A streaming companion
 * fetches bytes (HLS/DASH, a download, memory) and hands them here; skinema
 * never does the I/O itself, so the `--disable-network` guarantee holds
 * (FFmpeg cannot reach the network; it only pulls bytes through these
 * callbacks).
 *
 * The decoder pulls on its own thread: [read] is called when the demuxer
 * needs more bytes, [seek] when it repositions. A non-seekable live source
 * returns -1 from [seek] and [size]. Implementations must be usable from
 * that one decode thread for the decoder's lifetime; [close] runs when the
 * decoder closes.
 */
interface MediaSource : AutoCloseable {

    /**
     * Reads up to [length] bytes into [dst] starting at [offset]; returns
     * the number of bytes read, or -1 at end of stream. Must not return 0
     * (block until at least one byte is available, or report EOF).
     */
    fun read(dst: ByteArray, offset: Int, length: Int): Int

    /**
     * Repositions to absolute byte [position] and returns the new position,
     * or -1 when the source is not seekable (live). A forward-only stream
     * may always return -1; the demuxer then falls back to linear reads.
     */
    fun seek(position: Long): Long = -1

    /** Total size in bytes, or -1 when unknown (live / unbounded stream). */
    fun size(): Long = -1

    override fun close() {}
}
