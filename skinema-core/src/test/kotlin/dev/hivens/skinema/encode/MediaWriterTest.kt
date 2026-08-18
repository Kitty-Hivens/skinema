package dev.hivens.skinema.encode

import dev.hivens.skinema.libav.AudioDecoder
import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.VideoDecoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Encode acceptance: push synthetic RGBA frames through [MediaWriter] and
 * decode the result back with [VideoDecoder] -- a semantic round-trip
 * (frame count, geometry, a solid colour within yuv tolerance), the
 * project's "assert on meaning, not checksums" style. Gated on the loaded
 * libav actually carrying the encoder, so a decode-only bundle skips.
 */
class MediaWriterTest {

    private val dir: Path = Files.createTempDirectory("skinema-encode-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun solidGreen(w: Int, h: Int): ByteArray {
        val rgba = ByteArray(w * h * 4)
        for (p in 0 until w * h) {
            rgba[p * 4] = 0
            rgba[p * 4 + 1] = 255.toByte()
            rgba[p * 4 + 2] = 0
            rgba[p * 4 + 3] = 255.toByte()
        }
        return rgba
    }

    @Test
    fun `encodes RGBA frames to a file that decodes back`() {
        Fixtures.assumeLibraryEncoder("libx264")
        val w = 64
        val h = 64
        val fps = 10
        val frames = 10
        val out = dir.resolve("out.mp4")

        MediaWriter.open(
            out,
            VideoEncodeConfig("libx264", w, h, fps, options = mapOf("preset" to "ultrafast", "crf" to "23")),
        ).use { writer ->
            repeat(frames) { i -> writer.writeFrame(solidGreen(w, h), i * 1_000_000_000L / fps) }
            writer.finish()
        }

        assertTrue(Files.size(out) > 0, "the muxed file must not be empty")
        VideoDecoder.open(out).use { decoder ->
            val decoded = generateSequence { decoder.nextFrame() }.toList()
            // Exactly, not about. The tolerance that used to sit here was one
            // frame wide in the direction of a real defect and hid it: the
            // packets carried no duration, so the muxer gave the last sample
            // none, flagged it discard, and every clip came back a frame short.
            // Nothing merges a static tail -- it was thrown away.
            assertEquals(frames, decoded.size, "every pushed frame must come back")
            // Timing must survive the round-trip: a nanos/micros units slip in
            // the pts conversion compresses the whole clip ~1000x (10s -> 10ms).
            val interval = 1_000_000_000L / fps
            val expectedLast = (frames - 1) * interval
            assertTrue(
                decoded.last().ptsNanos in (expectedLast - 2 * interval)..(expectedLast + interval),
                "clip duration must round-trip: expected last pts ~$expectedLast ns, got ${decoded.last().ptsNanos}",
            )
            val first = decoded.first()
            assertEquals(w, first.width)
            assertEquals(h, first.height)
            val i = (h / 2 * w + w / 2) * 4
            val r = first.rgba[i].toInt() and 0xFF
            val g = first.rgba[i + 1].toInt() and 0xFF
            val b = first.rgba[i + 2].toInt() and 0xFF
            assertTrue(g > 180 && r < 80 && b < 80, "solid green must round-trip, got r=$r g=$g b=$b")
        }
    }

    /**
     * The documented `use { }` idiom, with something going wrong inside it.
     * close() used to free the natives and never write the container index,
     * so the caller was left with an mp4 that has no moov atom -- a file no
     * player opens, produced with no error and no exception. Fail-closed
     * cannot mean "and then leave a silently broken file behind".
     */
    @Test
    fun `a writer closed without finishing still leaves a readable file`() {
        Fixtures.assumeLibraryEncoder("libx264")
        val out = dir.resolve("aborted.mp4")
        runCatching {
            MediaWriter.open(
                out,
                VideoEncodeConfig("libx264", 64, 64, 10, options = mapOf("preset" to "ultrafast")),
            ).use { writer ->
                repeat(5) { i -> writer.writeFrame(solidGreen(64, 64), i * 100_000_000L) }
                throw IllegalStateException("the caller's own failure, mid-encode")
            }
        }
        assertTrue(Files.size(out) > 0, "the file must exist")
        VideoDecoder.open(out).use { decoder ->
            assertNotNull(decoder.nextFrame(), "an aborted encode must still open and decode")
        }
    }

    /**
     * The muxer's IO truncates the path on the way in, so a refusal after
     * that point had already destroyed whatever was there -- and then left
     * the wreck. The open promises to leave nothing behind, and a file the
     * caller already had is the last thing it may take.
     */
    @Test
    fun `a failed open leaves no output file behind`() {
        Fixtures.assumeLibraryEncoder("libx264")
        // A wav muxer takes one audio stream and no video, so the refusal
        // lands at the header -- after the IO has been opened and the file
        // truncated, which is the only window where this can go wrong.
        val out = dir.resolve("prior.wav")
        Files.writeString(out, "something the caller already had")
        val before = Files.size(out)
        val threw = runCatching {
            MediaWriter.open(out, VideoEncodeConfig("libx264", 64, 64, 10, options = mapOf("preset" to "ultrafast")))
        }.isFailure
        assertTrue(threw, "a container that cannot carry the stream must fail the open")
        assertTrue(before > 0 && !Files.exists(out), "a failed open must not leave a truncated file")
    }

    /** AutoCloseable's guarantee, and the natives are freed by then. */
    @Test
    fun `writing after close is refused rather than reaching freed memory`() {
        Fixtures.assumeLibraryEncoder("libx264")
        val writer = MediaWriter.open(
            dir.resolve("afterclose.mp4"),
            VideoEncodeConfig("libx264", 64, 64, 10, options = mapOf("preset" to "ultrafast")),
        )
        writer.writeFrame(solidGreen(64, 64), 0)
        writer.close()
        // The message matters, not just the type. Without the guard the call
        // reaches libav on freed pointers and is stopped -- if at all -- by
        // the confined arena's own liveness check, which raises the SAME
        // exception type from far deeper in. Only naming the guard tells the
        // two apart.
        assertEquals(
            "writeFrame after close()",
            assertFailsWith<IllegalStateException> { writer.writeFrame(solidGreen(64, 64), 100_000_000L) }.message,
        )
        assertEquals("finish after close()", assertFailsWith<IllegalStateException> { writer.finish() }.message)
        writer.close()
    }

    @Test
    fun `an unknown encoder fails closed`() {
        Fixtures.assumeDecodeEnvironment()
        val out = dir.resolve("nope.mp4")
        val threw = runCatching {
            MediaWriter.open(out, VideoEncodeConfig("definitely-not-a-codec", 64, 64, 10))
        }.isFailure
        assertTrue(threw, "an unknown encoder name must throw, not half-open")
    }

    @Test
    fun `encodes video and audio to a file that decodes both streams back`() {
        Fixtures.assumeLibraryEncoder("libx264")
        Fixtures.assumeLibraryEncoder("aac")
        val w = 64
        val h = 64
        val fps = 10
        val frames = 10
        val rate = 48000
        val out = dir.resolve("av.mp4")

        MediaWriter.open(
            out,
            VideoEncodeConfig("libx264", w, h, fps, options = mapOf("preset" to "ultrafast", "crf" to "23")),
            AudioEncodeConfig("aac", rate),
        ).use { writer ->
            // One second: 10 video frames plus 1s of stereo S16 silence in 0.1s chunks.
            val chunk = ByteArray(rate / 10 * 4)
            repeat(frames) { i ->
                writer.writeFrame(solidGreen(w, h), i * 1_000_000_000L / fps)
                writer.writeAudio(chunk)
            }
            writer.finish()
        }

        assertTrue(Files.size(out) > 0, "the muxed file must not be empty")
        VideoDecoder.open(out).use { d ->
            assertEquals(frames, generateSequence { d.nextFrame() }.count(), "every pushed frame must come back")
        }
        val audioDec = AudioDecoder.openOrNull(out)
        assertNotNull(audioDec, "the muxed file must carry a decodable audio stream")
        audioDec.use { assertNotNull(it.nextChunk(), "the audio stream must decode at least one chunk") }
    }

    /**
     * Every encoder takes its own input sample format, and planar float is
     * only the most common one. Written in as a constant it opened aac,
     * libmp3lame and libvorbis and refused everything else with a bare
     * EINVAL out of avcodec_open2 -- libopus and flac among them, both named
     * in AudioEncodeConfig's own documentation as examples of what to pass.
     *
     * Each encoder is skipped when the loaded library lacks it, so this
     * asserts over whatever the runner actually carries.
     */
    @Test
    fun `every audio encoder the library carries opens and decodes back`() {
        Fixtures.assumeLibraryEncoder("libx264")
        val rate = 48000
        val candidates = listOf("aac", "libopus", "flac", "alac", "libmp3lame", "libvorbis")
        val ran = mutableListOf<String>()
        for (codec in candidates) {
            if (!Fixtures.libraryHasEncoder(codec)) continue
            ran += codec
            val out = dir.resolve("audio-$codec.mkv")
            MediaWriter.open(
                out,
                VideoEncodeConfig("libx264", 64, 64, 10, options = mapOf("preset" to "ultrafast")),
                AudioEncodeConfig(codec, rate),
            ).use { writer ->
                val chunk = ByteArray(rate / 10 * 4)
                repeat(10) { i ->
                    writer.writeFrame(solidGreen(64, 64), i * 1_000_000_000L / 10)
                    writer.writeAudio(chunk)
                }
                writer.finish()
            }
            val decoded = AudioDecoder.openOrNull(out)
            assertNotNull(decoded, "$codec produced no decodable audio stream")
            decoded.use { assertNotNull(it.nextChunk(), "$codec produced a stream with no samples") }
        }
        // A run that silently matched nothing would pass on any code at all.
        assertTrue(ran.size >= 2, "too few encoders present to mean anything, ran $ran")
    }

    @Test
    fun `a rate the encoder cannot take is refused by name, not by errno`() {
        Fixtures.assumeLibraryEncoder("libx264")
        Fixtures.assumeLibraryEncoder("libopus")
        // libopus takes 48/24/16/12/8 kHz and nothing else; 44100 is the
        // rate a caller is most likely to arrive with.
        val out = dir.resolve("badrate.mkv")
        val failure = assertFailsWith<LibavException> {
            MediaWriter.open(
                out,
                VideoEncodeConfig("libx264", 64, 64, 10, options = mapOf("preset" to "ultrafast")),
                AudioEncodeConfig("libopus", 44_100),
            )
        }
        assertTrue(
            failure.message?.contains("44100") == true && failure.message?.contains("48000") == true,
            "the refusal must name the rate asked for and the rates on offer, said: ${failure.message}",
        )
    }

    /**
     * swscale converts RGB to YUV with its BT.601 default, and nothing said
     * so in the file. A stream that declares no matrix is read as BT.709 at
     * HD geometry -- by this decoder and by every other -- so every HD clip
     * this writer produced came back with shifted colour: measured 18 of 255
     * per channel, against 1 at 64x64 where the two conventions agree, which
     * is why only small fixtures ever looked right.
     */
    @Test
    fun `a colour survives the round trip at any geometry`() {
        Fixtures.assumeLibraryEncoder("libx264")
        for ((w, h) in listOf(64 to 64, 1280 to 720)) {
            val r = 220
            val g = 30
            val b = 40
            val out = dir.resolve("colour-${w}x$h.mkv")
            MediaWriter.open(
                out,
                // crf 0: what comes back is the colour conversion's doing and
                // not the encoder's.
                VideoEncodeConfig("libx264", w, h, 10, options = mapOf("preset" to "ultrafast", "crf" to "0")),
            ).use { writer ->
                val frame = ByteArray(w * h * 4).also {
                    for (i in 0 until w * h) {
                        it[i * 4] = r.toByte()
                        it[i * 4 + 1] = g.toByte()
                        it[i * 4 + 2] = b.toByte()
                        it[i * 4 + 3] = -1
                    }
                }
                repeat(4) { i -> writer.writeFrame(frame, i * 100_000_000L) }
                writer.finish()
            }
            VideoDecoder.open(out).use { d ->
                val f = assertNotNull(d.nextFrame(), "${w}x$h decoded no frame")
                val mid = ((f.height / 2) * f.width + f.width / 2) * 4
                val got = Triple(
                    f.rgba[mid].toInt() and 0xFF,
                    f.rgba[mid + 1].toInt() and 0xFF,
                    f.rgba[mid + 2].toInt() and 0xFF,
                )
                val err = maxOf(abs(got.first - r), maxOf(abs(got.second - g), abs(got.third - b)))
                assertTrue(err <= 4, "${w}x$h came back as $got instead of ($r, $g, $b), off by $err")
            }
            // The round trip above holds even untagged, because this decoder
            // guesses the same matrix from the geometry that the conversion
            // used. Everything else reading the file has to be told.
            val declared = probeStreamField(out, "color_space")
            val expected = if (w >= 1280) "bt709" else "smpte170m"
            assertEquals(expected, declared, "${w}x$h declares the wrong matrix")
            assertEquals("tv", probeStreamField(out, "color_range"), "${w}x$h declares the wrong range")
        }
    }

    /** One stream field as ffprobe reads it back out of the written file. */
    private fun probeStreamField(file: Path, field: String): String {
        val proc = ProcessBuilder(
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=$field", "-of", "default=nw=1:nk=1", file.toString(),
        ).redirectErrorStream(true).start()
        val text = proc.inputStream.readAllBytes().decodeToString().trim()
        proc.waitFor()
        return text
    }
}
