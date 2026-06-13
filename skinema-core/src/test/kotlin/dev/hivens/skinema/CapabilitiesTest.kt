package dev.hivens.skinema

import dev.hivens.skinema.libav.Fixtures
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The one positive assertion that the bundle under test is complete:
 * every capability CI marks mandatory in SKINEMA_REQUIRE_CAPS must
 * actually load here. The feature suites gate on the same capabilities
 * but SKIP when one is absent (a skip reads as green), and some skip
 * even earlier for an unrelated reason -- the webp suite needs a
 * libwebp encoder to build its fixture, which brew's ffmpeg lacks, so
 * it never reaches the load check on macOS. This test depends on
 * nothing but the load, so a broken bundled library fails here even
 * when every feature test skipped past it.
 *
 * With no caps required (a dev box) it asserts nothing. An unknown name
 * in the list fails too -- a typo would otherwise be a requirement no
 * one enforces.
 */
class CapabilitiesTest {

    @Test
    fun `every required capability loads`() {
        for (cap in Fixtures.requiredCaps) {
            if (cap !in Fixtures.knownCaps) {
                fail("SKINEMA_REQUIRE_CAPS lists unknown capability '$cap' -- known: ${Fixtures.knownCaps}")
            }
            assertTrue(
                Fixtures.capLoads(cap),
                "SKINEMA_REQUIRE_CAPS requires '$cap' but it did not load from the bundle under test",
            )
        }
    }
}
