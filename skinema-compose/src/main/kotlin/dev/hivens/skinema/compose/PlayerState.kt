package dev.hivens.skinema.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.hivens.skinema.player.VideoPlayer

/**
 * [VideoPlayer.state] as observable Compose state. Core exposes a plain
 * volatile (no coroutines, no listeners -- ROADMAP.md section 3), which a
 * composition cannot watch by itself; this polls it on the frame clock
 * (one volatile read per UI frame) and recomposes only on change. The
 * fallback branch of a consumer's player cell hangs off exactly this.
 */
@Composable
fun rememberPlayerState(player: VideoPlayer): VideoPlayer.State {
    var state by remember(player) { mutableStateOf(player.state) }
    LaunchedEffect(player) {
        while (true) {
            withFrameNanos { }
            val current = player.state
            if (state != current) state = current
        }
    }
    return state
}
