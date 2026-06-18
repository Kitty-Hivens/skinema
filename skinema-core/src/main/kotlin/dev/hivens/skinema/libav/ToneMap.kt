package dev.hivens.skinema.libav

import kotlin.math.exp
import kotlin.math.pow

/**
 * Software HDR -> SDR tone-mapping for the decode path. swscale hands us
 * 16-bit BT.2020 R'G'B' still encoded in the source transfer (PQ or HLG --
 * swscale converts the matrix and range but never the transfer); this
 * inverts the transfer to linear light, tone-maps that linear range down
 * to SDR (BT.2408 diffuse white -> ~SDR white region, with an extended-
 * Reinhard highlight knee), converts BT.2020 -> BT.709, and sRGB-encodes
 * to 8-bit. All pure Kotlin, so no native filter (zimg/libplacebo) and no
 * GPU -- the consumer still receives plain SDR RGBA8888.
 *
 * The hot path is LUT-driven: the 16-bit code -> linear mapping is a
 * 65536-entry table, the linear -> sRGB byte a small one, so per pixel
 * costs table reads, the Reinhard knee, one 3x3 matrix multiply and a
 * final lookup -- no `pow()` in the loop.
 *
 * Aesthetic knobs (the look, not the correctness, lives here):
 * [DIFFUSE_WHITE_NITS] and [MASTER_PEAK_NITS]. Diffuse white maps below
 * SDR white by design, trading some apparent brightness for highlight
 * headroom; tune these two if a consumer's HDR grades read too dim or too
 * hot. BT.2446 Method A is the heavier, more faithful future operator.
 */
internal class ToneMapper(transfer: HdrTransfer) {

    private val isHlg = transfer == HdrTransfer.HLG

    // 16-bit code -> linear. PQ folds the inverse-EOTF and the /diffuse-white
    // normalization in (so the value is already in SDR-white units). HLG can
    // only fold the inverse-OETF to scene-linear here; its OOTF depends on
    // the pixel's luma and so is applied per pixel below.
    private val inputLut = FloatArray(CODES).also { lut ->
        val maxCode = (CODES - 1).toDouble()
        for (c in 0 until CODES) {
            val v = c / maxCode
            lut[c] = if (isHlg) {
                hlgInverseOetf(v).toFloat()
            } else {
                (pqEotfNits(v) / DIFFUSE_WHITE_NITS).toFloat()
            }
        }
    }

    // Clamped SDR-linear [0,1] -> sRGB-encoded byte.
    private val oetfLut = ByteArray(OETF_N).also { lut ->
        val maxIdx = (OETF_N - 1).toDouble()
        for (i in 0 until OETF_N) {
            val encoded = srgbEncode(i / maxIdx)
            lut[i] = (encoded * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()
        }
    }

    // HLG OOTF luma term Yscene^(gamma-1), over quantized scene luma [0,1].
    private val hlgLumaLut = if (isHlg) {
        FloatArray(LUMA_N).also { lut ->
            val maxIdx = (LUMA_N - 1).toDouble()
            for (i in 0 until LUMA_N) lut[i] = (i / maxIdx).pow(HLG_GAMMA - 1.0).toFloat()
        }
    } else {
        FloatArray(0)
    }

    /**
     * Tone-maps a 16-bit RGBA64LE frame ([pixelCount] pixels, 4 unsigned LE
     * shorts each) into [out] as 8-bit RGBA8888. Alpha is forced opaque --
     * HDR video carries none.
     */
    fun toneMap(src: ShortArray, out: ByteArray, pixelCount: Int) {
        var s = 0
        var d = 0
        repeat(pixelCount) {
            var r = inputLut[src[s].toInt() and 0xFFFF]
            var g = inputLut[src[s + 1].toInt() and 0xFFFF]
            var b = inputLut[src[s + 2].toInt() and 0xFFFF]

            if (isHlg) {
                // OOTF: display-linear = peak * Yscene^(gamma-1) * E, then
                // normalized to diffuse-white units like the PQ branch.
                val yScene = (HLG_LR * r + HLG_LG * g + HLG_LB * b).coerceIn(0f, 1f)
                val factor = HLG_PEAK_OVER_WHITE * hlgLumaLut[(yScene * (LUMA_N - 1)).toInt()]
                r *= factor
                g *= factor
                b *= factor
            }

            // Highlight knee in BT.2020 linear (per channel -- hue-safe,
            // analytic endpoints), then BT.2020 -> BT.709.
            r = reinhardKnee(r, REINHARD_WHITE)
            g = reinhardKnee(g, REINHARD_WHITE)
            b = reinhardKnee(b, REINHARD_WHITE)
            val r709 = M00 * r + M01 * g + M02 * b
            val g709 = M10 * r + M11 * g + M12 * b
            val b709 = M20 * r + M21 * g + M22 * b

            out[d] = oetfByte(r709)
            out[d + 1] = oetfByte(g709)
            out[d + 2] = oetfByte(b709)
            out[d + 3] = OPAQUE
            s += 4
            d += 4
        }
    }

    private fun oetfByte(linear: Float): Byte {
        val clamped = if (linear <= 0f) 0f else if (linear >= 1f) 1f else linear
        return oetfLut[(clamped * (OETF_N - 1)).toInt()]
    }

    enum class HdrTransfer { PQ, HLG }

    private companion object {
        const val CODES = 65536
        const val OETF_N = 4096
        const val LUMA_N = 1024
        const val OPAQUE = 255.toByte()

        // BT.2020 -> BT.709 linear (D65 shared; rows sum to 1.0).
        const val M00 = 1.660491f
        const val M01 = -0.587641f
        const val M02 = -0.072850f
        const val M10 = -0.124550f
        const val M11 = 1.132900f
        const val M12 = -0.008349f
        const val M20 = -0.018151f
        const val M21 = -0.100579f
        const val M22 = 1.118730f

        // HLG OOTF: BT.2020 luma weights, peak luminance and system gamma.
        const val HLG_LR = 0.2627f
        const val HLG_LG = 0.6780f
        const val HLG_LB = 0.0593f
        val HLG_PEAK_OVER_WHITE = (HLG_PEAK_NITS / DIFFUSE_WHITE_NITS).toFloat()

        val REINHARD_WHITE = (MASTER_PEAK_NITS / DIFFUSE_WHITE_NITS).toFloat()
    }
}

// -- Pure transfer/colour math (top-level internal so tests hit it directly,
//    like swsCoefficientsFor) -------------------------------------------------

/** SDR diffuse ("graphics") white, BT.2408. The tone-map's unity point. */
internal const val DIFFUSE_WHITE_NITS = 203.0

/** Assumed HDR mastering peak; the Reinhard knee's white point. A knob. */
internal const val MASTER_PEAK_NITS = 1000.0

/** HLG nominal display peak and system gamma (BT.2100, Lw = 1000). */
internal const val HLG_PEAK_NITS = 1000.0
internal const val HLG_GAMMA = 1.2

/**
 * SMPTE ST 2084 (PQ) inverse-EOTF: normalized code [0,1] -> display
 * luminance in cd/m^2 ([0, 10000]). The five constants satisfy
 * c1 = c3 - c2 + 1.
 */
internal fun pqEotfNits(code: Double): Double {
    if (code <= 0.0) return 0.0
    val p = code.pow(1.0 / PQ_M2)
    val num = (p - PQ_C1).coerceAtLeast(0.0)
    val den = PQ_C2 - PQ_C3 * p
    if (den <= 0.0) return 10000.0
    return 10000.0 * (num / den).pow(1.0 / PQ_M1)
}

/**
 * BT.2100 HLG inverse-OETF: signal [0,1] -> scene-linear [0,1]. The OOTF
 * (scene -> display) is luma-dependent and applied by [ToneMapper].
 */
internal fun hlgInverseOetf(signal: Double): Double {
    val e = signal.coerceIn(0.0, 1.0)
    return if (e <= 0.5) e * e / 3.0 else (exp((e - HLG_A_C) / HLG_A_A) + HLG_A_B) / 12.0
}

/** sRGB OETF: linear [0,1] -> encoded [0,1]. The SDR surface is sRGB. */
internal fun srgbEncode(linear: Double): Double {
    val c = linear.coerceIn(0.0, 1.0)
    return if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055
}

/**
 * Extended Reinhard with a white point: maps 0 -> 0 and [whitePoint] -> 1,
 * monotone, near-linear toward black. Per channel on linear light.
 */
internal fun reinhardKnee(x: Float, whitePoint: Float): Float {
    if (x <= 0f) return 0f
    return x * (1f + x / (whitePoint * whitePoint)) / (1f + x)
}

/** BT.2020 -> BT.709 linear, row-major; exposed for the row-sum test. */
internal val BT2020_TO_BT709 = doubleArrayOf(
    1.660491, -0.587641, -0.072850,
    -0.124550, 1.132900, -0.008349,
    -0.018151, -0.100579, 1.118730,
)

private const val PQ_M1 = 0.1593017578125
private const val PQ_M2 = 78.84375
private const val PQ_C1 = 0.8359375
private const val PQ_C2 = 18.8515625
private const val PQ_C3 = 18.6875

private const val HLG_A_A = 0.17883277
private const val HLG_A_B = 0.28466892
private const val HLG_A_C = 0.55991073
