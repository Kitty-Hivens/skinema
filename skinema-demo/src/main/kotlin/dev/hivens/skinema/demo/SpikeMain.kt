package dev.hivens.skinema.demo

import dev.hivens.skinema.libav.Libav
import dev.hivens.skinema.libav.VideoDecoder
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories

/**
 * M0 decode spike (ROADMAP.md section 11): open a local video, decode up
 * to N frames through the FFM bindings, dump a few PNGs for eyeballing,
 * and report the decode+convert cost. PNG encoding is deliberately kept
 * out of the measured time -- it is spike tooling, not pipeline.
 *
 *   ./gradlew :skinema-demo:spike -Pinput=<video> -Pout=<dir> [-Pframes=N]
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: spike <input-video> <out-dir> [max-frames]" }
    val input = Path.of(args[0])
    val outDir = Path.of(args[1]).also { it.createDirectories() }
    val maxFrames = args.getOrNull(2)?.toInt() ?: 120

    println("libav: " + Libav.versions.entries.joinToString { "${it.key.baseName} ${it.value}" })

    VideoDecoder.open(input).use { decoder ->
        println(
            "stream #${decoder.streamIndex}, time_base ${decoder.timeBaseNum}/${decoder.timeBaseDen}",
        )

        var frames = 0
        var decodeNanos = 0L
        var width = 0
        var height = 0
        while (frames < maxFrames) {
            val t0 = System.nanoTime()
            val frame = decoder.nextFrame() ?: break
            decodeNanos += System.nanoTime() - t0

            width = frame.width
            height = frame.height
            if (frames < 4 || frames % 30 == 0) {
                val file = outDir.resolve("frame-%04d.png".format(frames)).toFile()
                dumpPng(frame, file)
                println("frame %4d  pts %8.3f s  -> %s".format(frames, frame.ptsNanos / 1e9, file.name))
            }
            frames++
        }

        val totalMs = decodeNanos / 1_000_000.0
        val perFrameMs = if (frames > 0) totalMs / frames else 0.0
        println("decoded $frames frames of ${width}x$height")
        println(
            "decode+convert: %.1f ms total, %.2f ms/frame, ~%.0f fps capacity"
                .format(totalMs, perFrameMs, if (perFrameMs > 0) 1000.0 / perFrameMs else 0.0),
        )
    }
}

private fun dumpPng(frame: VideoDecoder.RgbaFrame, file: File) {
    val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
    val row = IntArray(frame.width)
    val rgba = frame.rgba
    var i = 0
    for (y in 0 until frame.height) {
        for (x in 0 until frame.width) {
            val r = rgba[i].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF
            val a = rgba[i + 3].toInt() and 0xFF
            row[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            i += 4
        }
        image.setRGB(0, y, frame.width, 1, row, 0, frame.width)
    }
    ImageIO.write(image, "png", file)
}
