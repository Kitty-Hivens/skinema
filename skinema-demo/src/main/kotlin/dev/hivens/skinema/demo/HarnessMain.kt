package dev.hivens.skinema.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.hivens.skinema.compose.VideoSurface
import dev.hivens.skinema.compose.rememberPlayerState
import dev.hivens.skinema.player.VideoPlayer
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Windowed background harness (ROADMAP.md, M3): the consumer-shaped
 * scenarios a launcher background system will throw at the library --
 * several players at once, surfaces leaving and re-entering the
 * composition while players keep running, and a failed source rendering
 * as the consumer's fallback, all next to a live RSS/heap ticker.
 *
 *   ./gradlew :skinema-demo:harness -Pvideo=<file> [-Pplayers=N] [-PreadAhead=N]
 */
fun main(args: Array<String>) {
    val video = Path.of(requireNotNull(args.firstOrNull()) { "usage: harness <video> [players] [churn-seconds]" })
    val playerCount = args.getOrNull(1)?.toInt() ?: 3
    val churnSeconds = args.getOrNull(2)?.toLong() ?: 0L
    val readAhead = System.getProperty("skinema.demo.readAhead")?.toInt() ?: 1
    application {
        Window(onCloseRequest = ::exitApplication, title = "skinema harness") {
            // N live players plus one doomed source: the fallback cell
            // must come from state, not from a crash.
            val players = remember { List(playerCount) { VideoPlayer(video, loop = true, readAheadFrames = readAhead) } }
            val doomed = remember { VideoPlayer(video.resolveSibling("does-not-exist.mp4"), loop = true) }
            DisposableEffect(Unit) {
                onDispose {
                    players.forEach { it.close() }
                    doomed.close()
                }
            }

            var surfacesMounted by remember { mutableStateOf(true) }
            var rss by remember { mutableLongStateOf(0L) }
            var heap by remember { mutableLongStateOf(0L) }
            LaunchedEffect(Unit) {
                while (true) {
                    rss = rssMbOrZero()
                    heap = heapMbNow()
                    delay(1.seconds)
                }
            }

            // Leak hunt mode: churn mount/unmount on a timer and log the
            // post-GC heap baseline -- raw heap readings are GC sawtooth;
            // only a ratcheting post-GC baseline is a real leak.
            if (churnSeconds > 0) {
                LaunchedEffect(Unit) {
                    var cycles = 0
                    while (true) {
                        delay(churnSeconds.seconds)
                        surfacesMounted = !surfacesMounted
                        cycles++
                        if (cycles % 6 == 0) {
                            System.gc()
                            delay(300.milliseconds)
                            println("churn cycles=$cycles postGcHeapMb=${heapMbNow()} rssMb=${rssMbOrZero()}")
                        }
                    }
                }
            }

            Column(Modifier.fillMaxSize().background(Color(0xFF101014)).padding(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { surfacesMounted = !surfacesMounted }) {
                        Text(if (surfacesMounted) "Unmount surfaces" else "Mount surfaces")
                    }
                    Text("rss ${rss}M heap ${heap}M", color = Color.White)
                    Text(
                        "players keep running while unmounted; remount must resume instantly",
                        color = Color.Gray,
                    )
                }

                if (surfacesMounted) {
                    val all = players + doomed
                    val columns = generateSequence(1) { it + 1 }.first { it * it >= all.size }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        all.chunked(columns).forEach { row ->
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { player -> PlayerCell(player, Modifier.weight(1f)) }
                                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("surfaces unmounted -- decode threads still alive", color = Color.Gray)
                    }
                }
            }
        }
    }
}

/** A background consumer's cell: video when alive, an explicit fallback when not. */
@Composable
private fun PlayerCell(player: VideoPlayer, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color(0xFF1A1A22))) {
        when (val state = rememberPlayerState(player)) {
            is VideoPlayer.State.Failed -> Box(
                Modifier.fillMaxSize().background(Color(0xFF402030)),
                contentAlignment = Alignment.Center,
            ) {
                Text("fallback: ${state.cause.message?.take(60)}", color = Color.White)
            }
            else -> VideoSurface(player, Modifier.fillMaxSize())
        }
    }
}

private fun rssMbOrZero(): Long {
    val status = Path.of("/proc/self/status")
    if (!java.nio.file.Files.isReadable(status)) return 0
    val line = java.nio.file.Files.readAllLines(status).firstOrNull { it.startsWith("VmRSS:") } ?: return 0
    return line.removePrefix("VmRSS:").trim().split(Regex("\\s+")).first().toLong() / 1024
}

private fun heapMbNow(): Long {
    val rt = Runtime.getRuntime()
    return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
}
