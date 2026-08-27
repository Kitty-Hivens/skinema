package dev.hivens.skinema.encode

import dev.hivens.skinema.libav.AudioDecoder
import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.libav.Libav
import dev.hivens.skinema.libav.LibavAbi
import dev.hivens.skinema.libav.LibavException
import dev.hivens.skinema.libav.VideoDecoder
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

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
        val before = Files.readAllBytes(out)
        val threw = runCatching {
            MediaWriter.open(out, VideoEncodeConfig("libx264", 64, 64, 10, options = mapOf("preset" to "ultrafast")))
        }.isFailure
        assertTrue(threw, "a container that cannot carry the stream must fail the open")
        // Gone, or exactly as the caller left it. Which of the two depends on
        // how far the open got before it refused, and that depends on the
        // build: a library without a wav muxer cannot infer one from the
        // extension and never opens the IO that truncates, so the file is
        // simply untouched. What must never happen is the middle -- the file
        // truncated by the IO and then abandoned.
        if (Files.exists(out)) {
            assertContentEquals(before, Files.readAllBytes(out), "a failed open truncated the caller's file")
        }
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

    /**
     * A file with sound and no picture, which is what the audio-only
     * containers exist for and what nothing here could produce until the
     * writer grew a second entry point.
     *
     * Asserted on both halves, because either alone is satisfied by the old
     * behaviour: the sound has to decode back, AND the file must carry no
     * video stream. A writer that quietly kept a video track would pass the
     * first assertion with an empty picture nobody asked for.
     */
    @Test
    fun `an audio-only writer produces a file with sound and no picture`() {
        Fixtures.assumeDecodeEnvironment()
        val codec = listOf("libopus", "flac", "aac").firstOrNull { Fixtures.libraryHasEncoder(it) }
            ?: return
        val rate = 48000
        // The container follows the codec: Opus in Ogg, FLAC native, AAC in
        // mp4 -- each the ordinary home for a track of that kind.
        val ext = when (codec) {
            "libopus" -> "opus"
            "flac" -> "flac"
            else -> "m4a"
        }
        val out = dir.resolve("sound-only.$ext")

        MediaWriter.open(out, AudioEncodeConfig(codec, rate)).use { writer ->
            val chunk = ByteArray(rate / 10 * 4)
            repeat(10) { writer.writeAudio(chunk) }
            writer.finish()
        }

        assertTrue(Files.size(out) > 0, "the audio-only file must not be empty")
        val decoded = AudioDecoder.openOrNull(out)
        assertNotNull(decoded, "$codec produced no decodable audio stream")
        decoded.use { assertNotNull(it.nextChunk(), "$codec produced a stream with no samples") }
        // No picture: opening it for video is refused, the same answer the
        // decode side already gives a file whose only video stream is a
        // cover.
        assertFailsWith<LibavException>("a file written without video must not open as video") {
            VideoDecoder.open(out).close()
        }
    }

    /** The other half of the split: asking an audio-only writer for a frame. */
    @Test
    fun `writeFrame on an audio-only writer is refused by name`() {
        Fixtures.assumeDecodeEnvironment()
        val codec = listOf("libopus", "flac", "aac").firstOrNull { Fixtures.libraryHasEncoder(it) }
            ?: return
        MediaWriter.open(dir.resolve("refuse.mkv"), AudioEncodeConfig(codec, 48000)).use { writer ->
            // The message matters, not the type: without the guard this
            // reaches a null video track and arrives as an NPE from deeper
            // in, which says nothing about what the caller did wrong.
            assertEquals(
                "this MediaWriter has no video stream",
                assertFailsWith<LibavException> { writer.writeFrame(solidGreen(64, 64), 0) }.message,
            )
        }
    }

    /**
     * The two encoders the bundle gained with the LGPL encode tier. Both are
     * skipped where the loaded library lacks them, so this asserts over what
     * the runner actually carries -- and against a bundle that has them, it
     * is the only thing here that proves they encode rather than merely
     * appearing in a manifest.
     */
    @Test
    fun `the AV1 encoder round-trips through a file that decodes back`() {
        Fixtures.assumeLibraryEncoder("libsvtav1")
        // A round trip needs BOTH ends, and gating on the encoder alone is
        // how this failed rather than skipped on a bundle carrying one and
        // not the other -- measured against exactly such a build, where the
        // file encoded and then had nothing to read it. The tiers that ship
        // carry both; a custom FEATURES set need not.
        assumeTrue(
            Fixtures.libraryHasDecoder("libdav1d") || Fixtures.libraryHasDecoder("av1"),
            "the bundle encodes AV1 but cannot decode it back -- skipping the round trip",
        )
        val w = 64
        val h = 64
        val fps = 10
        val frames = 10
        val out = dir.resolve("av1.mkv")

        MediaWriter.open(
            out,
            // SVT-AV1's fastest preset: this asserts that the encoder runs at
            // all, and its slower presets cost seconds per frame.
            VideoEncodeConfig("libsvtav1", w, h, fps, options = mapOf("preset" to "12")),
        ).use { writer ->
            repeat(frames) { i -> writer.writeFrame(solidGreen(w, h), i * 1_000_000_000L / fps) }
            writer.finish()
        }

        assertTrue(Files.size(out) > 0, "the AV1 file must not be empty")
        VideoDecoder.open(out).use { decoder ->
            val decoded = generateSequence { decoder.nextFrame() }.toList()
            assertEquals(frames, decoded.size, "every pushed frame must come back out of AV1")
            val first = assertNotNull(decoded.firstOrNull())
            assertEquals(w, first.width, "the AV1 file must keep its width")
            assertEquals(h, first.height, "the AV1 file must keep its height")
        }
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

    /**
     * What an encoder takes is asked, not assumed. Written as a constant,
     * `avcodec_open2` refused every encoder wanting 4:2:2, 4:4:4 or planar
     * RGB with a bare errno -- prores, dnxhd, libx264rgb, qtrle.
     *
     * Asked of the decision rather than of an encoder, because no bundle
     * carries one that refuses yuv420p: the whitelist is libx264, libx265,
     * the two VAAPI encoders, aac and flac. The round-trip below covers the
     * real thing wherever a system build supplies it, and skips otherwise --
     * which left the rule itself untested on every machine that matters.
     */
    @Test
    fun `an encoder is given a format from its own list`() {
        // A NULL advertisement means "anything", which is the same answer as
        // the leading preference.
        assertEquals(
            LibavAbi.AV_PIX_FMT_YUV420P,
            MediaWriter.pickPixelFormat("libx264", null),
        )
        assertEquals(
            LibavAbi.AV_PIX_FMT_YUV420P,
            MediaWriter.pickPixelFormat("libx264", intArrayOf(LibavAbi.AV_PIX_FMT_YUV420P, LibavAbi.AV_PIX_FMT_YUV444P)),
        )
        // The qtrle case: no planar yuv at all, so the preference walks to RGB.
        assertEquals(
            LibavAbi.AV_PIX_FMT_RGB24,
            MediaWriter.pickPixelFormat("qtrle", intArrayOf(LibavAbi.AV_PIX_FMT_RGB24, LibavAbi.AV_PIX_FMT_YUVA420P)),
        )
        // The libx264rgb case: planar RGB leads over a format outside the list.
        assertEquals(
            LibavAbi.AV_PIX_FMT_GBRP,
            MediaWriter.pickPixelFormat("libx264rgb", intArrayOf(LibavAbi.AV_PIX_FMT_YUVA420P, LibavAbi.AV_PIX_FMT_GBRP)),
        )
        // Nothing the preference names: hand over what there is and let
        // sws_getContext say what it cannot do, rather than guess here.
        assertEquals(
            LibavAbi.AV_PIX_FMT_YUVA420P,
            MediaWriter.pickPixelFormat("exotic", intArrayOf(LibavAbi.AV_PIX_FMT_YUVA420P)),
        )
        assertFailsWith<LibavException>("an encoder advertising nothing is a refusal, not a default") {
            MediaWriter.pickPixelFormat("empty", intArrayOf())
        }

        // The same rule on the audio side, which had it first.
        assertEquals(
            LibavAbi.AV_SAMPLE_FMT_FLTP,
            MediaWriter.pickSampleFormat("aac", null),
        )
        assertEquals(
            LibavAbi.AV_SAMPLE_FMT_S16,
            MediaWriter.pickSampleFormat("flac", intArrayOf(LibavAbi.AV_SAMPLE_FMT_S16, LibavAbi.AV_SAMPLE_FMT_S32)),
        )
        assertFailsWith<LibavException> { MediaWriter.pickSampleFormat("empty", intArrayOf()) }
    }

    /**
     * The layout is negotiated the way the rate and the sample format next to
     * it are. An encoder taking only mono -- nellymoser, g722, half the speech
     * codecs -- used to reach avcodec_open2 with stereo written in and come
     * back with a bare EINVAL, which is the failure shape those two checks
     * exist to replace.
     *
     * Layouts are structs, so the candidates are built rather than named, and
     * the answers are compared rather than equated.
     */
    @Test
    fun `the encoder's channel layout is picked, not assumed`() {
        Fixtures.assumeDecodeEnvironment()
        Arena.ofConfined().use { arena ->
            fun layout(channels: Int) = arena.allocate(LibavAbi.ChannelLayout.SIZEOF)
                .also { Libav.avChannelLayoutDefault(it, channels) }
            val mono = layout(1)
            val stereo = layout(2)
            val surround = layout(6)

            fun assertSame(expected: MemorySegment, actual: MemorySegment, message: String) =
                assertEquals(0, Libav.avChannelLayoutCompare(expected, actual), message)

            assertSame(
                stereo,
                MediaWriter.pickChannelLayout("aac", arena, null),
                "an encoder that advertises nothing takes what this writer is fed",
            )
            assertSame(
                stereo,
                MediaWriter.pickChannelLayout("libopus", arena, listOf(mono, stereo)),
                "stereo in, stereo out: the conversion that costs nothing",
            )
            assertSame(
                mono,
                MediaWriter.pickChannelLayout("nellymoser", arena, listOf(mono)),
                "a mono-only encoder must be given mono, not refused at open",
            )
            assertSame(
                surround,
                MediaWriter.pickChannelLayout("exotic", arena, listOf(surround, mono)),
                "past stereo the encoder's own first choice wins",
            )
            assertFailsWith<LibavException> { MediaWriter.pickChannelLayout("empty", arena, emptyList()) }
        }
    }

    /**
     * The container index is the one step of [MediaWriter.finish] that cannot
     * be retried: av_write_trailer deinitialises the muxer whether it
     * succeeded or not, so a second call reads memory the first one freed and
     * arrives as a SIGSEGV rather than a return code. What a retry CAN do is
     * tell the truth, and it used to report success -- the caller freed the
     * disk, called finish() again, was told the encode had finished, and kept
     * a file with no index that nothing opens.
     *
     * Staged through /dev/full, which accepts nothing and answers ENOSPC.
     * The extension is what picks the muxer, so the writer is pointed at a
     * symlink; the header and a second of 64x64 video fit inside avio's own
     * buffer, so the device is not touched until the trailer flushes it --
     * which is exactly the failure being staged.
     */
    @Test
    fun `a finish that could not write the index says so again`() {
        Fixtures.assumeLibraryEncoder("libx264")
        val full = Path.of("/dev/full")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.exists(full),
            "no /dev/full to refuse the write",
        )
        val out = dir.resolve("nospace.mkv")
        Files.createSymbolicLink(out, full)
        val w = 64
        val h = 64
        val writer = MediaWriter.open(out, VideoEncodeConfig("libx264", w, h, 10))
        try {
            val frame = solidGreen(w, h)
            repeat(10) { i -> writer.writeFrame(frame, i * 100_000_000L) }
            val first = assertFailsWith<LibavException>("the trailer must refuse on a full device") {
                writer.finish()
            }
            val again = assertFailsWith<Throwable>("a retry must not report a success that never happened") {
                writer.finish()
            }
            assertEquals(first, again, "the retry must report the failure it already had")
        } finally {
            writer.close()
        }
    }

    @Test
    fun `an encoder that will not take yuv420p is given what it does take`() {
        // qtrle accepts rgb24, argb, rgb555be and gray -- no yuv420p at all.
        // Written as a constant, avcodec_open2 refused it with a bare errno,
        // the same shape the audio side's sample format had before it was
        // negotiated. Lossless, so the colour is the conversion's doing.
        Fixtures.assumeLibraryEncoder("qtrle")
        val w = 64
        val h = 64
        val out = dir.resolve("qtrle.mov")
        MediaWriter.open(out, VideoEncodeConfig("qtrle", w, h, 10)).use { writer ->
            val frame = ByteArray(w * h * 4).also {
                for (i in 0 until w * h) {
                    it[i * 4] = 220.toByte()
                    it[i * 4 + 1] = 30
                    it[i * 4 + 2] = 40
                    it[i * 4 + 3] = -1
                }
            }
            repeat(3) { i -> writer.writeFrame(frame, i * 100_000_000L) }
            writer.finish()
        }
        VideoDecoder.open(out).use { d ->
            val f = assertNotNull(d.nextFrame(), "qtrle decoded no frame")
            val mid = ((f.height / 2) * f.width + f.width / 2) * 4
            val got = Triple(
                f.rgba[mid].toInt() and 0xFF,
                f.rgba[mid + 1].toInt() and 0xFF,
                f.rgba[mid + 2].toInt() and 0xFF,
            )
            val err = maxOf(abs(got.first - 220), maxOf(abs(got.second - 30), abs(got.third - 40)))
            assertTrue(err <= 2, "an rgb encoder round trip came back as $got, off by $err")
        }
    }
}
