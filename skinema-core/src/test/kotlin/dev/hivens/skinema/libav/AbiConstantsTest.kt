package dev.hivens.skinema.libav

import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The hand-transcribed constants OTHER than pixel formats, asked of the
 * library that defines them.
 *
 * [PixelFormatAbiTest] exists because one pixel format was transcribed wrong
 * -- `GBRP` written as 168, which is `GRAY10LE` -- and every test that used it
 * named the same constant on both sides of the assertion, so the number could
 * have been anything. That lesson was applied to pixel formats and to nothing
 * else: the codec ids and sample formats sit in [LibavAbi] on the same terms,
 * transcribed by hand from the oracle's output, and no test asks the library
 * whether they mean what they are named for.
 *
 * They are not decorative. The two video ids decide whether libvpx is
 * preferred over the native decoder (the webm alpha side-channel), the
 * subtitle ids are the fallback text/bitmap split for a codec the descriptor
 * table does not describe, and the sample formats drive every conversion the
 * audio path sets up. A wrong number in any of them is silent: the wrong
 * decoder, the wrong branch, or samples read as the wrong width.
 */
class AbiConstantsTest {

    private fun codecName(id: Int): String {
        val ptr = Libav.avcodecGetName(id)
        if (ptr == MemorySegment.NULL) return "<none>"
        return ptr.reinterpret(Long.MAX_VALUE).getString(0)
    }

    private fun sampleFormatName(fmt: Int): String {
        val ptr = Libav.avGetSampleFmtName(fmt)
        if (ptr == MemorySegment.NULL) return "<none>"
        return ptr.reinterpret(Long.MAX_VALUE).getString(0)
    }

    @Test
    fun `every transcribed codec id is the codec it is named for`() {
        Fixtures.assumeDecodeEnvironment()
        val expected = mapOf(
            "eia_608" to LibavAbi.AV_CODEC_ID_EIA_608,
            "dvd_subtitle" to LibavAbi.AV_CODEC_ID_DVD_SUBTITLE,
            "ssa" to LibavAbi.AV_CODEC_ID_SSA,
            "mov_text" to LibavAbi.AV_CODEC_ID_MOV_TEXT,
            "hdmv_pgs_subtitle" to LibavAbi.AV_CODEC_ID_HDMV_PGS_SUBTITLE,
            "subrip" to LibavAbi.AV_CODEC_ID_SUBRIP,
            "webvtt" to LibavAbi.AV_CODEC_ID_WEBVTT,
            "ass" to LibavAbi.AV_CODEC_ID_ASS,
            "vp8" to LibavAbi.AV_CODEC_ID_VP8,
            "vp9" to LibavAbi.AV_CODEC_ID_VP9,
        )
        for ((name, id) in expected) {
            assertEquals(name, codecName(id), "AV_CODEC_ID for $name is transcribed as $id")
        }
    }

    @Test
    fun `every transcribed sample format is the one it is named for`() {
        Fixtures.assumeDecodeEnvironment()
        val expected = mapOf(
            "s16" to LibavAbi.AV_SAMPLE_FMT_S16,
            "s32" to LibavAbi.AV_SAMPLE_FMT_S32,
            "flt" to LibavAbi.AV_SAMPLE_FMT_FLT,
            "s16p" to LibavAbi.AV_SAMPLE_FMT_S16P,
            "s32p" to LibavAbi.AV_SAMPLE_FMT_S32P,
            "fltp" to LibavAbi.AV_SAMPLE_FMT_FLTP,
        )
        for ((name, fmt) in expected) {
            assertEquals(name, sampleFormatName(fmt), "AV_SAMPLE_FMT for $name is transcribed as $fmt")
        }
    }

    /**
     * The planar/packed pairs must not collide: the audio path chooses between
     * them by number, and two names landing on one value would read as a
     * working conversion that silently interleaves the wrong samples.
     */
    @Test
    fun `the planar and packed sample formats are distinct numbers`() {
        Fixtures.assumeDecodeEnvironment()
        val all = listOf(
            LibavAbi.AV_SAMPLE_FMT_S16,
            LibavAbi.AV_SAMPLE_FMT_S32,
            LibavAbi.AV_SAMPLE_FMT_FLT,
            LibavAbi.AV_SAMPLE_FMT_S16P,
            LibavAbi.AV_SAMPLE_FMT_S32P,
            LibavAbi.AV_SAMPLE_FMT_FLTP,
        )
        assertEquals(all.size, all.toSet().size, "two sample formats share a number: $all")
    }
}
