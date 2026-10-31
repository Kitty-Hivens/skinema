package dev.hivens.skinema.android

import android.media.MediaDataSource
import dev.hivens.skinema.libav.MediaSource
import java.io.IOException

/**
 * One consumer [MediaSource] and where it stands. Shared by every platform
 * reader over it (the extractor and the metadata retriever both read the
 * same source), so each read is placed against the source's real position
 * rather than against what one reader last did.
 */
internal class SourceCursor(val source: MediaSource) {

    private var position = 0L

    /**
     * The seek this source refused, kept because the platform reader swallows
     * what a data source throws and reports only that it could not open.
     */
    @Volatile
    var refusal: String? = null
        private set

    /**
     * Reads at absolute [at]. The platform readers ask for bytes anywhere in
     * the stream, so a read away from where the source stands is a seek
     * first, and a source that cannot seek is refused at the first such read,
     * by name. The desktop demuxer falls back to linear reads there and the
     * platform extractor cannot, which the guide states.
     */
    @Synchronized
    fun readAt(at: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (at != position) {
            val landed = source.seek(at)
            if (landed < 0) {
                val why = "the MediaSource cannot seek, and the platform reader asks for offset $at"
                refusal = why
                throw IOException(why)
            }
            position = landed
        }
        val read = source.read(buffer, offset, size)
        if (read < 0) return -1
        position += read
        return read
    }
}

/**
 * A [SourceCursor] as the platform readers take one. Closing it does not
 * close the consumer's source: the retriever closes its data source when it
 * is released, mid-open, and the source still has a decoder to feed. The
 * decoder that owns the source closes it.
 */
internal class MediaSourceDataSource(private val cursor: SourceCursor) : MediaDataSource() {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        cursor.readAt(position, buffer, offset, size)

    override fun getSize(): Long = cursor.source.size()

    override fun close() {}
}
