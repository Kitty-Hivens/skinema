package dev.hivens.skinema.android

import android.media.MediaFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The matrix rule on its own. The decoder suite cannot reach the fallback on
 * every device: Codec2 decoders fill in the colour aspects of an untagged
 * stream themselves, by its resolution, so the declared standard arrives set
 * and the rule's own branch never runs. A decoder that reports nothing does
 * reach it, and this is what it then does.
 */
class YuvMatrixDeviceTest {

    @Test
    fun aDeclaredStandardWinsOverTheGeometry() {
        assertEquals(YuvMatrix.BT601, YuvMatrix.of(MediaFormat.COLOR_STANDARD_BT601_NTSC, 1920, 1080))
        assertEquals(YuvMatrix.BT709, YuvMatrix.of(MediaFormat.COLOR_STANDARD_BT709, 320, 240))
        assertEquals(YuvMatrix.BT2020, YuvMatrix.of(MediaFormat.COLOR_STANDARD_BT2020, 3840, 2160))
    }

    @Test
    fun nothingDeclaredMeansBt709ForHdAndBt601Below() {
        assertEquals(YuvMatrix.BT709, YuvMatrix.of(null, 1280, 720))
        assertEquals(YuvMatrix.BT709, YuvMatrix.of(null, 720, 1280))
        assertEquals(YuvMatrix.BT601, YuvMatrix.of(null, 720, 576))
        assertEquals(YuvMatrix.BT601, YuvMatrix.of(null, 320, 240))
    }

    @Test
    fun tenBitLimitedRangeSpansTheWholeSixteenBitSignal() {
        val out = ShortArray(4)
        val convert = YuvToRgba(YuvMatrix.BT2020, fullRange = false)
        // White and black at the ten-bit limited range's ends, grey chroma.
        convert.pixel10(940, 512, 512, out, 0)
        assertEquals(listOf(65535, 65535, 65535), out.take(3).map { it.toInt() and 0xFFFF }, "white")
        convert.pixel10(64, 512, 512, out, 0)
        assertEquals(listOf(0, 0, 0), out.take(3).map { it.toInt() and 0xFFFF }, "black")
        // Full BT.2020 red: its luma and Cr at the top of its 896 codes. The
        // eight-bit scales leave it at code 1020 of 1023.
        convert.pixel10(294, 512, 960, out, 0)
        val red = out[0].toInt() and 0xFFFF
        assertTrue(red >= 65_400, "red at its limited-range peak reads $red")
    }
}
