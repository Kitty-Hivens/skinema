package dev.hivens.skinema.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.hivens.skinema.compose.VideoSurface
import dev.hivens.skinema.player.VideoPlayer
import java.nio.file.Path

fun main(args: Array<String>) {
    val video = Path.of(requireNotNull(args.firstOrNull()) { "usage: skinema-demo <video>" })
    application {
        Window(onCloseRequest = ::exitApplication, title = "skinema demo") {
            val player = remember { VideoPlayer(video, loop = true) }
            DisposableEffect(player) {
                onDispose { player.close() }
            }
            VideoSurface(player, Modifier.fillMaxSize())
        }
    }
}
