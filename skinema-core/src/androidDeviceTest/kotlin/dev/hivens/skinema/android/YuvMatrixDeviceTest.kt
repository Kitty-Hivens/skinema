package dev.hivens.skinema.android

import android.media.MediaFormat
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
