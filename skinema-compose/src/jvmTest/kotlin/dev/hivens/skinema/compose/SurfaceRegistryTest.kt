package dev.hivens.skinema.compose

import dev.hivens.skinema.player.VideoPlayer
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two surfaces on one player is a mistake with no symptom of its own: the
 * mailbox hands each published frame to whoever polls first, so the two split
 * the stream and both merely look slow. The registry is what turns that into
 * a sentence on stderr, and it has to be exact about which player it is
 * counting -- a false report would send a consumer looking for a second
 * surface that does not exist.
 *
 * A player over a path that is not there is enough to be counted; what the
 * file contains never enters into it.
 */
class SurfaceRegistryTest {

    private fun player(): VideoPlayer = VideoPlayer(Path.of("no-such-file.mkv"), loop = false)

    @Test
    fun `the first surface is silent and the second is not`() {
        player().use { player ->
            assertFalse(SurfaceRegistry.add(player), "one surface on one player is the ordinary case")
            assertTrue(SurfaceRegistry.add(player), "the second surface must be reported")
            SurfaceRegistry.remove(player)
            SurfaceRegistry.remove(player)
            assertFalse(SurfaceRegistry.add(player), "a player whose surfaces went away starts over")
            SurfaceRegistry.remove(player)
        }
    }

    @Test
    fun `one player's surface is not another's second`() {
        player().use { first ->
            player().use { second ->
                assertFalse(SurfaceRegistry.add(first))
                assertFalse(SurfaceRegistry.add(second), "a different player carries its own count")
                SurfaceRegistry.remove(first)
                SurfaceRegistry.remove(second)
            }
        }
    }
}
