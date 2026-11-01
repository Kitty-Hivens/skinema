@file:OptIn(UnstableApi::class)

package dev.hivens.skinema.android

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import dev.hivens.skinema.libav.MediaSource
import java.io.IOException

/**
 * A consumer's stream as Media3 reads one. Each open takes a fresh reader from
 * [readers] (a cursor over a seekable stream) and places it where the data
 * spec asks, and the close gives it back.
 */
internal class StreamDataSource(private val readers: () -> MediaSource) : BaseDataSource(false) {

    private var reader: MediaSource? = null
    private var uri: Uri? = null
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val r = readers()
        reader = r
        uri = dataSpec.uri
        if (dataSpec.position > 0 && r.seek(dataSpec.position) != dataSpec.position) {
            throw IOException("the stream cannot be read from offset ${dataSpec.position}")
        }
        val size = r.size()
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            size >= 0 -> (size - dataSpec.position).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val r = reader ?: throw IOException("read before open")
        val want = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val n = r.read(buffer, offset, want)
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val r = reader ?: return
        reader = null
        uri = null
        runCatching { r.close() }
        transferEnded()
    }
}

/**
 * Files to [FileDataSource], the player's own stream URIs to a
 * [StreamDataSource] over whatever [streamFor] answers for them. Nothing else
 * is wired, so no network data source exists anywhere in the player.
 */
internal class PlayerDataSourceFactory(private val streamFor: (Uri) -> (() -> MediaSource)?) : DataSource.Factory {
    override fun createDataSource(): DataSource = Dispatching()

    private inner class Dispatching : DataSource {
        private var current: DataSource? = null
        private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            val readers = if (dataSpec.uri.scheme == STREAM_SCHEME) {
                streamFor(dataSpec.uri) ?: throw IOException("no stream behind ${dataSpec.uri}")
            } else {
                null
            }
            val next: DataSource = if (readers != null) StreamDataSource(readers) else FileDataSource()
            listeners.forEach(next::addTransferListener)
            current = next
            return next.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            current?.read(buffer, offset, length) ?: throw IOException("read before open")

        override fun getUri(): Uri? = current?.uri

        override fun close() {
            val c = current ?: return
            current = null
            c.close()
        }
    }

    companion object {
        const val STREAM_SCHEME = "skinema-stream"
    }
}
