package dev.hivens.skinema.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.hivens.skinema.player.VideoPlayer
import kotlinx.coroutines.runInterruptible

/**
 * [VideoPlayer.state] as observable Compose state. Core exposes a plain
 * volatile (no coroutines, no listeners, ROADMAP.md section 3), which a
 * composition cannot watch by itself. This waits on
 * [VideoPlayer.awaitChange] and recomposes only on change.
 *
 * It used to poll on the frame clock, one read per UI frame, and a read per
 * frame is a frame asked for: a player cell that only wanted to know whether
 * to show a spinner kept its window redrawing at the display's full refresh,
 * paused player and all.
 *
 * The count is read before the state, so a change landing between the two is
 * one the wait returns for at once rather than one it sleeps through. The
 * loop ends once the state can no longer change.
 */
@Composable
fun rememberPlayerState(player: VideoPlayer): VideoPlayer.State {
    var state by remember(player) { mutableStateOf(player.state) }
    LaunchedEffect(player) {
        var seen = player.changeCount
        while (true) {
            val current = player.state
            if (state != current) state = current
            if (current is VideoPlayer.State.Failed || current is VideoPlayer.State.Closed) return@LaunchedEffect
            seen = runInterruptible(changeWaits) { player.awaitChange(seen, CHANGE_WAIT_NANOS) }
        }
    }
    return state
}
