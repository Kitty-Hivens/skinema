package dev.hivens.skinema.libav

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Path

/** The generated fixtures, copied out of the test APK onto the device once. */
internal object DeviceFixtures {

    private val dir: File by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("skinema-fixtures").apply { mkdirs() }
    }

    fun path(name: String): Path {
        val file = dir.resolve(name)
        if (!file.exists()) {
            val stream = javaClass.getResourceAsStream("/fixtures/$name")
                ?: error("fixture $name is not in the test APK; the androidFixtures task builds it")
            stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return file.toPath()
    }

    /** A seekable in-memory [MediaSource] over a fixture's bytes. */
    fun memorySource(name: String): MediaSource {
        val bytes = path(name).toFile().readBytes()
        return object : MediaSource {
            var position = 0
            override fun read(dst: ByteArray, offset: Int, length: Int): Int {
                if (position >= bytes.size) return -1
                val n = minOf(length, bytes.size - position)
                System.arraycopy(bytes, position, dst, offset, n)
                position += n
                return n
            }

            override fun seek(position: Long): Long {
                this.position = position.toInt().coerceIn(0, bytes.size)
                return this.position.toLong()
            }

            override fun size(): Long = bytes.size.toLong()
        }
    }

    /** A source that refuses to seek, the live-stream shape. */
    fun forwardOnlySource(name: String): MediaSource {
        val file = RandomAccessFile(path(name).toFile(), "r")
        return object : MediaSource {
            override fun read(dst: ByteArray, offset: Int, length: Int): Int = file.read(dst, offset, length)
            override fun close() = file.close()
        }
    }
}

/** The RGBA of the pixel at the centre of [frame]. */
internal fun centre(frame: VideoDecoder.RgbaFrame): IntArray {
    val i = ((frame.height / 2) * frame.width + frame.width / 2) * 4
    return IntArray(3) { frame.rgba[i + it].toInt() and 0xFF }
}
