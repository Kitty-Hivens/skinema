@file:OptIn(UnstableApi::class)

package dev.hivens.skinema.android

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.inspector.MediaExtractorCompat
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.MediaSource
import java.nio.ByteBuffer

/**
 * What the decoders read samples through: the platform extractor for a file
 * or a stream that can seek, Media3's extractor for one that reads forward
 * only, since the platform's asks for bytes anywhere in the stream.
 */
internal interface Demuxer {
    val trackCount: Int
    fun getTrackFormat(index: Int): MediaFormat
    fun selectTrack(index: Int)
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int
    val sampleTime: Long
    fun advance(): Boolean

    /** To the keyframe at or before [micros]. */
    fun seekToPrevious(micros: Long)
    fun release()
}

internal class PlatformDemuxer(private val extractor: MediaExtractor) : Demuxer {
    override val trackCount: Int get() = extractor.trackCount
    override fun getTrackFormat(index: Int): MediaFormat = extractor.getTrackFormat(index)
    override fun selectTrack(index: Int) = extractor.selectTrack(index)
    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int = extractor.readSampleData(buffer, offset)
    override val sampleTime: Long get() = extractor.sampleTime
    override fun advance(): Boolean = extractor.advance()
    override fun seekToPrevious(micros: Long) = extractor.seekTo(micros, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    override fun release() = extractor.release()
}

/**
 * Media3's extractor over [readers], one per open, for a stream that reads
 * forward only. Its Matroska reader does not seek to the Cues an ordinary
 * muxer leaves at the end, which such a stream could not reach.
 */
internal class StreamDemuxer(readers: () -> MediaSource) : Demuxer {
    private val extractor = MediaExtractorCompat(
        DefaultExtractorsFactory().setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES),
        PlayerDataSourceFactory { { readers() } },
    )

    init {
        extractor.setDataSource(Uri.parse("${PlayerDataSourceFactory.STREAM_SCHEME}://decoder"), 0)
    }

    override val trackCount: Int get() = extractor.trackCount
    override fun getTrackFormat(index: Int): MediaFormat = extractor.getTrackFormat(index)
    override fun selectTrack(index: Int) = extractor.selectTrack(index)
    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int = extractor.readSampleData(buffer, offset)
    override val sampleTime: Long get() = extractor.sampleTime
    override fun advance(): Boolean = extractor.advance()
    /**
     * Refused by name. Media3's extractor answers a seek on a stream it cannot
     * seek by starting again from byte zero, which a decoder would hand out as
     * frames from the start where the time asked for was somewhere else.
     */
    override fun seekToPrevious(micros: Long): Unit = throw LibavException("a stream that reads forward only cannot seek")
    override fun release() = extractor.release()
}
