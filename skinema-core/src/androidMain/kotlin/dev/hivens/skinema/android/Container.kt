package dev.hivens.skinema.android

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import dev.hivens.skinema.Debug
import dev.hivens.skinema.libav.AudioTrack
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.MediaSource
import dev.hivens.skinema.libav.SubtitleTrack
import java.nio.file.Path

/**
 * Where a decoder's bytes come from: a file, or a consumer's [MediaSource].
 * The decoder that opened an [Input] closes it, which for a source is the one
 * close the consumer's source gets.
 */
internal sealed interface Input : AutoCloseable {
    class File(val path: Path) : Input
    class Source(source: MediaSource) : Input {
        val cursor = SourceCursor(source)
    }

    val label: String
        get() = when (this) {
            is File -> path.toString()
            is Source -> "custom source"
        }

    override fun close() {
        if (this is Source) cursor.source.close()
    }
}

/**
 * An extractor over [input]. Refusals come back as [LibavException], the type
 * every decoder in this library fails with, so a consumer catches one thing
 * whichever platform it runs on.
 */
internal fun openExtractor(input: Input): MediaExtractor {
    val extractor = MediaExtractor()
    try {
        when (input) {
            is Input.File -> extractor.setDataSource(input.path.toString())
            is Input.Source -> extractor.setDataSource(MediaSourceDataSource(input.cursor))
        }
    } catch (e: Exception) {
        extractor.release()
        throw LibavException("the platform extractor cannot open ${input.label}: ${e.message}")
    }
    return extractor
}

/** Index of the first track whose type starts with [prefix] ("video/", "audio/"), or -1. */
internal fun MediaExtractor.firstTrack(prefix: String): Int =
    (0 until trackCount).firstOrNull { getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true } ?: -1

internal fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

internal fun MediaFormat.longOrNull(key: String): Long? = if (containsKey(key)) getLong(key) else null

/** Every audio track, keyed by its index in the container as the desktop keys them. */
internal fun MediaExtractor.audioTracks(): List<AudioTrack> = (0 until trackCount).mapNotNull { i ->
    val f = getTrackFormat(i)
    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) return@mapNotNull null
    AudioTrack(
        streamIndex = i,
        language = f.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it != "und" },
        title = null,
        channels = f.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 0,
        sampleRate = f.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0,
        isDefault = f.intOrNull(MediaFormat.KEY_IS_DEFAULT) == 1,
    )
}

/** The subtitle tracks the platform extractor exposes. */
internal fun MediaExtractor.subtitleTracks(): List<SubtitleTrack> = (0 until trackCount).mapNotNull { i ->
    val f = getTrackFormat(i)
    val mime = f.getString(MediaFormat.KEY_MIME) ?: return@mapNotNull null
    val text = mime.startsWith("text/") || mime == "application/x-subrip" || mime == "application/x-ssa"
    val bitmap = mime == "application/pgs" || mime == "application/vobsub" || mime == "application/dvbsubs"
    if (!text && !bitmap) return@mapNotNull null
    SubtitleTrack(
        id = i,
        streamIndex = i,
        language = f.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it != "und" },
        title = null,
        codecName = mime,
        isText = text,
        isDefault = f.intOrNull(MediaFormat.KEY_IS_DEFAULT) == 1,
        isForced = f.intOrNull(MediaFormat.KEY_IS_FORCED_SUBTITLE) == 1,
    )
}

/**
 * The container's tags and cover art, read through the platform retriever.
 * It names a fixed set of keys rather than handing the container's own
 * dictionary over, so the tags here are that set under the names the
 * desktop's demuxers use for the same fields.
 */
internal class ContainerInfo(val tags: Map<String, String>, val coverArt: ByteArray?) {
    companion object {
        private val KEYS = listOf(
            MediaMetadataRetriever.METADATA_KEY_TITLE to "title",
            MediaMetadataRetriever.METADATA_KEY_ARTIST to "artist",
            MediaMetadataRetriever.METADATA_KEY_ALBUM to "album",
            MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST to "album_artist",
            MediaMetadataRetriever.METADATA_KEY_COMPOSER to "composer",
            MediaMetadataRetriever.METADATA_KEY_GENRE to "genre",
            MediaMetadataRetriever.METADATA_KEY_DATE to "date",
            MediaMetadataRetriever.METADATA_KEY_YEAR to "year",
            MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER to "track",
            MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER to "disc",
            MediaMetadataRetriever.METADATA_KEY_WRITER to "lyricist",
        )

        fun read(input: Input): ContainerInfo {
            val retriever = MediaMetadataRetriever()
            return try {
                when (input) {
                    is Input.File -> retriever.setDataSource(input.path.toString())
                    is Input.Source -> retriever.setDataSource(MediaSourceDataSource(input.cursor))
                }
                val tags = buildMap {
                    for ((key, name) in KEYS) retriever.extractMetadata(key)?.takeIf { it.isNotBlank() }?.let { put(name, it) }
                }
                ContainerInfo(tags, retriever.embeddedPicture)
            } catch (e: Exception) {
                // Metadata is the optional half of an open: a file whose tags
                // the retriever cannot read still plays.
                Debug.trace("reading container metadata of ${input.label}", e)
                ContainerInfo(emptyMap(), null)
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}
