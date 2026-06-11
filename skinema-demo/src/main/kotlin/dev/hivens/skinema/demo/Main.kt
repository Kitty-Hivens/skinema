package dev.hivens.skinema.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import dev.hivens.skinema.compose.VideoScale
import dev.hivens.skinema.compose.VideoSurface
import dev.hivens.skinema.compose.rememberPlayerState
import dev.hivens.skinema.player.VideoPlayer
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

private const val SEEK_STEP_NANOS = 10_000_000_000L

fun main(args: Array<String>) {
    val video = Path.of(requireNotNull(args.firstOrNull()) { "usage: skinema-demo <video> [sound]" })
    val sound = args.getOrNull(1) == "sound"
    val readAhead = System.getProperty("skinema.demo.readAhead")?.toInt() ?: 1
    application {
        Window(onCloseRequest = ::exitApplication, title = "skinema demo") {
            val player = remember { VideoPlayer(video, loop = true, audio = sound, readAheadFrames = readAhead) }
            DisposableEffect(player) {
                onDispose { player.close() }
            }

            var paused by remember { mutableStateOf(false) }
            var volume by remember { mutableFloatStateOf(1f) }
            var positionMs by remember { mutableLongStateOf(0L) }
            LaunchedEffect(player) {
                while (true) {
                    positionMs = player.positionNanos() / 1_000_000
                    kotlinx.coroutines.delay(200.milliseconds)
                }
            }

            Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
                    // A viewer letterboxes; Cover (the background default) crops
                    // whenever the window\'s aspect drifts from the video\'s.
                    VideoSurface(player, Modifier.fillMaxSize(), scale = VideoScale.Fit)
                    if (rememberPlayerState(player) is VideoPlayer.State.Seeking) {
                        Text(
                            "seeking...",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center)
                                .background(Color(0xAA000000)).padding(12.dp),
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = {
                        if (paused) player.resume() else player.pause()
                        paused = !paused
                    }) {
                        Text(if (paused) "Play" else "Pause")
                    }
                    Button(onClick = { player.seekBy(-SEEK_STEP_NANOS) }) {
                        Text("-10s")
                    }
                    Button(onClick = { player.seekBy(SEEK_STEP_NANOS) }) {
                        Text("+10s")
                    }
                    Text(
                        "%d:%02d.%03d".format(positionMs / 60_000, positionMs / 1_000 % 60, positionMs % 1_000),
                        color = Color.White,
                    )
                    if (sound) {
                        Text("vol", color = Color.Gray)
                        Slider(
                            value = volume,
                            onValueChange = {
                                volume = it
                                player.setVolume(it)
                            },
                            modifier = Modifier.width(120.dp),
                        )
                    }
                    when (val state = rememberPlayerState(player)) {
                        is VideoPlayer.State.Failed -> Text(
                            "failed: ${state.cause.message}",
                            color = Color(0xFFFF6B6B),
                        )
                        else -> Text("state: ${state::class.simpleName}", color = Color.Gray)
                    }
                }
            }
        }
    }
}
