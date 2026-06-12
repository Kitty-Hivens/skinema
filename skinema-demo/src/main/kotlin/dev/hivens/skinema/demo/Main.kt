package dev.hivens.skinema.demo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.hivens.skinema.compose.VideoScale
import dev.hivens.skinema.compose.VideoSurface
import dev.hivens.skinema.compose.rememberPlayerState
import dev.hivens.skinema.libav.AudioTrack
import dev.hivens.skinema.player.VideoPlayer
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.skia.Image as SkiaImage

private const val SEEK_STEP_NANOS = 10_000_000_000L

private val RATE_STEPS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

private fun rateLabel(rate: Float): String =
    if (rate % 1f == 0f) "${rate.toInt()}x" else "${rate}x"

private fun trackLabel(track: AudioTrack): String = buildString {
    append(track.language ?: "und")
    track.title?.let { append(" ").append(it) }
    append(" ").append(track.sampleRate / 1000).append("kHz")
}

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
            var durationMs by remember { mutableLongStateOf(0L) }
            var dragMs by remember { mutableStateOf<Long?>(null) }
            var tracks by remember { mutableStateOf(emptyList<AudioTrack>()) }
            var activeTrack by remember { mutableStateOf<Int?>(null) }
            var coverBytes by remember { mutableStateOf<ByteArray?>(null) }
            var chapterTitle by remember { mutableStateOf("") }
            var rate by remember { mutableFloatStateOf(1f) }
            LaunchedEffect(player) {
                while (true) {
                    positionMs = player.positionNanos() / 1_000_000
                    durationMs = (player.durationNanos ?: 0) / 1_000_000
                    tracks = player.audioTracks
                    activeTrack = player.activeAudioTrack
                    coverBytes = player.coverArt
                    rate = player.rate
                    chapterTitle = player.chapters
                        .lastOrNull { it.startNanos <= positionMs * 1_000_000 }?.title ?: ""
                    kotlinx.coroutines.delay(200.milliseconds)
                }
            }

            Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // The cover sits BEHIND the surface: frameless playback
                    // (music with embedded art) shows it for free, video
                    // frames paint over it.
                    coverBytes?.let { bytes ->
                        val bitmap = remember(bytes) {
                            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                        }
                        Image(bitmap, contentDescription = null, modifier = Modifier.align(Alignment.Center))
                    }
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

                if (durationMs > 0) {
                    // Timeline: dragging scrubs with instant keyframe
                    // landings; letting go settles on the exact frame.
                    Slider(
                        value = (dragMs ?: positionMs).coerceIn(0, durationMs).toFloat() / durationMs,
                        onValueChange = {
                            val target = (it * durationMs).toLong()
                            dragMs = target
                            player.seek(target * 1_000_000, exact = false)
                        },
                        onValueChangeFinished = {
                            dragMs?.let { player.seek(it * 1_000_000, exact = true) }
                            dragMs = null
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
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
                    // Skip buttons land on keyframes: instant picture and
                    // sound beat frame-exact targets for this gesture.
                    Button(onClick = { player.seekBy(-SEEK_STEP_NANOS, exact = false) }) {
                        Text("-10s")
                    }
                    Button(onClick = { player.seekBy(SEEK_STEP_NANOS, exact = false) }) {
                        Text("+10s")
                    }
                    // Frame steps leave the player paused on the frame.
                    Button(onClick = {
                        player.stepBackward()
                        paused = true
                    }) {
                        Text("<|")
                    }
                    Button(onClick = {
                        player.stepForward()
                        paused = true
                    }) {
                        Text("|>")
                    }
                    Box {
                        var rateMenu by remember { mutableStateOf(false) }
                        Button(onClick = { rateMenu = true }) {
                            Text(rateLabel(rate))
                        }
                        DropdownMenu(expanded = rateMenu, onDismissRequest = { rateMenu = false }) {
                            RATE_STEPS.forEach { step ->
                                DropdownMenuItem(onClick = {
                                    player.setRate(step)
                                    rateMenu = false
                                }) {
                                    Text((if (step == rate) "* " else "  ") + rateLabel(step))
                                }
                            }
                        }
                    }
                    val total = if (durationMs > 0) {
                        " / %d:%02d".format(durationMs / 60_000, durationMs / 1_000 % 60)
                    } else {
                        ""
                    }
                    Text(
                        "%d:%02d.%03d".format(positionMs / 60_000, positionMs / 1_000 % 60, positionMs % 1_000) + total,
                        color = Color.White,
                    )
                    if (chapterTitle.isNotEmpty()) {
                        Text(chapterTitle, color = Color.Gray)
                    }
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
                    if (tracks.size > 1) {
                        Box {
                            var menuOpen by remember { mutableStateOf(false) }
                            Button(onClick = { menuOpen = true }) {
                                Text(tracks.firstOrNull { it.streamIndex == activeTrack }?.let(::trackLabel) ?: "audio")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                tracks.forEach { track ->
                                    DropdownMenuItem(onClick = {
                                        player.selectAudioTrack(track.streamIndex)
                                        menuOpen = false
                                    }) {
                                        Text((if (track.streamIndex == activeTrack) "* " else "  ") + trackLabel(track))
                                    }
                                }
                            }
                        }
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
