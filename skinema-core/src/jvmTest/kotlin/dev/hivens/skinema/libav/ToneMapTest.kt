package dev.hivens.skinema.libav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exact, native-free checks of the tone-mapper's colour math against the
 * published standards (SMPTE ST 2084, ITU-R BT.2100/2408, sRGB). The
 * numbers come from the specs, not from running the implementation, so
 * these are non-circular -- they pin the maths the integration tests then
 * exercise end to end.
 */
class ToneMapTest {

    @Test
    fun `PQ inverse-EOTF matches ST 2084 reference points`() {
        assertEquals(0.0, pqEotfNits(0.0), 1e-6)
        assertEquals(10000.0, pqEotfNits(1.0), 1.0)
        assertEquals(92.5, pqEotfNits(0.5), 2.0, "PQ code 0.5 is ~92 nits")
        assertEquals(1000.0, pqEotfNits(0.7518), 10.0, "PQ code 0.7518 is ~1000 nits")
        // The standard's constant invariant c1 = c3 - c2 + 1.
        assertEquals(0.8359375, 18.6875 - 18.8515625 + 1.0, 1e-9)
    }

    @Test
    fun `PQ inverse-EOTF is monotonic`() {
        var prev = -1.0
        var v = 0.0
        while (v <= 1.0001) {
            val nits = pqEotfNits(v)
            assertTrue(nits >= prev, "PQ must be monotonic, dipped at $v")
            prev = nits
            v += 0.01
        }
    }

    @Test
    fun `HLG inverse-OETF endpoints and branch continuity`() {
        assertEquals(0.0, hlgInverseOetf(0.0), 1e-6)
        assertEquals(1.0, hlgInverseOetf(1.0), 1e-3)
        assertEquals(1.0 / 12.0, hlgInverseOetf(0.5), 1e-4, "the two branches meet at 0.5")
        assertEquals(hlgInverseOetf(0.5), hlgInverseOetf(0.5 + 1e-6), 1e-4, "continuous across the branch")
    }

    @Test
    fun `sRGB OETF endpoints and the linear-power join`() {
        assertEquals(0.0, srgbEncode(0.0), 1e-9)
        assertEquals(1.0, srgbEncode(1.0), 1e-9)
        val join = 0.0031308
        assertEquals(12.92 * join, srgbEncode(join), 1e-4, "linear and power branches join")
    }

    @Test
    fun `BT2020 to BT709 preserves neutral and pushes primaries out of gamut`() {
        val m = BT2020_TO_BT709
        for (row in 0 until 3) {
            val sum = m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2]
            assertEquals(1.0, sum, 1e-3, "row $row must preserve neutral grey")
        }
        // Pure BT.2020 red leaves the BT.709 gamut: red overshoots, the
        // other two go negative.
        assertTrue(m[0] > 1.0, "2020 red overshoots 709 red")
        assertTrue(m[3] < 0.0 && m[6] < 0.0, "2020 red is out of the 709 gamut")
    }

    @Test
    fun `Reinhard knee maps its white point to one and stays monotonic`() {
        val w = 5f
        assertEquals(0f, reinhardKnee(0f, w), 1e-6f)
        assertEquals(1f, reinhardKnee(w, w), 1e-4f, "the white point maps to 1.0")
        var prev = -1f
        var x = 0f
        while (x <= 2f * w) {
            val y = reinhardKnee(x, w)
            assertTrue(y >= prev, "knee must be monotonic, dipped at $x")
            prev = y
            x += 0.05f
        }
    }
}
