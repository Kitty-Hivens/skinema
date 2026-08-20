package dev.hivens.skinema.libav

import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Asks the LOADED library what each transcribed pixel-format number means.
 *
 * These constants come from `tools/layout-oracle.c` compiled against the
 * pinned headers, and the ones the encoder's format preference needed were
 * added to [LibavAbi] by hand without going through it. One was wrong --
 * `GBRP` written as 168, which is `GRAY10LE` -- so a planar-RGB encoder
 * never matched its own preference entry and one advertising 10-bit
 * grayscale would have been handed it as if it were colour.
 *
 * What let it survive is the shape worth remembering: every test that used
 * the constant named it on BOTH sides of the assertion, so the number could
 * be anything at all and the test still passed. The name comes from the
 * library here, which is the one source that cannot agree with a mistake.
 */
class PixelFormatAbiTest {

    private fun nameOf(pixFmt: Int): String {
        val ptr = Libav.avGetPixFmtName(pixFmt)
        if (ptr == MemorySegment.NULL) return "<none>"
        return ptr.reinterpret(Long.MAX_VALUE).getString(0)
    }

    @Test
    fun `every transcribed pixel format is the one it is named for`() {
        Fixtures.assumeDecodeEnvironment()
        val expected = mapOf(
            "yuv420p" to LibavAbi.AV_PIX_FMT_YUV420P,
            "yuv422p" to LibavAbi.AV_PIX_FMT_YUV422P,
            "yuv444p" to LibavAbi.AV_PIX_FMT_YUV444P,
            "yuva420p" to LibavAbi.AV_PIX_FMT_YUVA420P,
            "gbrp" to LibavAbi.AV_PIX_FMT_GBRP,
            "rgb24" to LibavAbi.AV_PIX_FMT_RGB24,
            "rgba" to LibavAbi.AV_PIX_FMT_RGBA,
            "rgba64le" to LibavAbi.AV_PIX_FMT_RGBA64LE,
            "nv12" to LibavAbi.AV_PIX_FMT_NV12,
            "vaapi" to LibavAbi.AV_PIX_FMT_VAAPI,
            "cuda" to LibavAbi.AV_PIX_FMT_CUDA,
            "videotoolbox_vld" to LibavAbi.AV_PIX_FMT_VIDEOTOOLBOX,
            "d3d11" to LibavAbi.AV_PIX_FMT_D3D11,
            "dxva2_vld" to LibavAbi.AV_PIX_FMT_DXVA2_VLD,
            "qsv" to LibavAbi.AV_PIX_FMT_QSV,
        )
        for ((name, value) in expected) {
            assertEquals(name, nameOf(value), "AV_PIX_FMT for $name is transcribed as $value")
        }
    }

    /**
     * The other half: the sentinel must name nothing, or a format lookup
     * that fell through would read as a real format somewhere.
     */
    @Test
    fun `the none sentinel names no format`() {
        Fixtures.assumeDecodeEnvironment()
        assertEquals("<none>", nameOf(LibavAbi.AV_PIX_FMT_NONE))
    }
}
