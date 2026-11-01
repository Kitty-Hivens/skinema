package dev.hivens.skinema.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hivens.skinema.compose.VideoScale
import dev.hivens.skinema.compose.VideoSurface
import dev.hivens.skinema.compose.rememberPlayerState
import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.libav.MediaInput
import dev.hivens.skinema.player.VideoPlayer
import kotlinx.coroutines.delay
import java.io.File

/** Plays the videos pushed into the app's own external files directory. */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = getExternalFilesDir(null) ?: filesDir
        setContent { DemoScreen(dir) }
    }
}

private val label = TextStyle(color = Color.White, fontSize = 14.sp)

@Composable
private fun Button(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Color(0x99000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { BasicText(text, style = label) }
}

private fun clock(nanos: Long?): String {
    if (nanos == null) return "--:--"
    val seconds = nanos / 1_000_000_000L
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun DemoScreen(dir: File) {
    val videos = remember { dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty() }
    if (videos.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            BasicText("adb push <video> ${dir.absolutePath}/", style = label)
        }
        return
    }
    var index by remember { mutableIntStateOf(0) }
    var sound by remember { mutableStateOf(true) }
    var scale by remember { mutableStateOf(VideoScale.Fit) }
    val player = remember(index, sound) {
        VideoPlayer(MediaInput.File(videos[index].toPath()), loop = true, audio = sound, hardware = HwAccel.AUTO)
    }
    DisposableEffect(player) { onDispose { player.closeAsync() } }
    val state = rememberPlayerState(player)
    var position by remember(player) { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            position = player.positionNanos()
            delay(200)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(player, Modifier.fillMaxSize(), scale = scale, background = Color.Black)
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                "${videos[index].name}  ${state::class.simpleName}  ${clock(position)} / ${clock(player.durationNanos)}" +
                    "  x${player.rate}  hw=${player.hardwareActive}  tracks=${player.audioTracks.size}",
                style = label,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("-10s") { player.seekBy(-10_000_000_000L, exact = false) }
                Button(if (state == VideoPlayer.State.Playing) "pause" else "play") {
                    if (state == VideoPlayer.State.Playing) player.pause() else player.resume()
                }
                Button("+10s") { player.seekBy(10_000_000_000L, exact = false) }
                Button("step") { player.stepForward() }
                Button("back") { player.stepBackward() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("x${player.rate}") {
                    player.setRate(
                        when (player.rate) {
                            1f -> 1.5f
                            1.5f -> 2f
                            2f -> 0.5f
                            else -> 1f
                        },
                    )
                }
                Button(if (sound) "mute" else "sound") { sound = !sound }
                Button(scale.name) { scale = if (scale == VideoScale.Fit) VideoScale.Cover else VideoScale.Fit }
                Button("next") { index = (index + 1) % videos.size }
            }
        }
    }
}
