package dev.hivens.skinema.demo

import dev.hivens.skinema.player.VideoPlayer
import java.nio.file.Path

/**
 * Headless seek diagnostic: drives scripted seeks against a real file and
 * (with SKINEMA_DEBUG_SEEK set) prints each landing's keyframe gap and
 * decode cost. Measures the freeze the demo's buttons produce without
 * needing the GUI.
 *
 *   SKINEMA_DEBUG_SEEK=1 ./gradlew :skinema-demo:seekbench -Pvideo=<file>
 */
fun main(args: Array<String>) {
    val video = Path.of(requireNotNull(args.firstOrNull()) { "usage: seekbench <video>" })
    val audio = args.getOrNull(1) != "silent"
    VideoPlayer(video, loop = true, audio = audio).use { player ->
        Thread.sleep(800)
        player.seek(50_000_000_000L)
        Thread.sleep(1_000)

        // After a backward seek, sample the clock for a second. A healthy
        // resume advances ~monotonically from the target; a stall shows the
        // clock frozen, then a jump -- exactly the user's "freezes, recovers
        // with unknown delay".
        println("== backward seek to 40s, then clock samples ==")
        player.seek(40_000_000_000L)
        var prev = -1L
        repeat(20) {
            Thread.sleep(50)
            val ms = player.positionNanos() / 1_000_000
            val delta = if (prev < 0) 0 else ms - prev
            println("t=${it * 50}ms pos=${ms}ms d=${delta}ms state=${player.state::class.simpleName}")
            prev = ms
        }

        println("== burst of 6 backward seekBy(-3s), 40ms apart, then clock samples ==")
        repeat(6) {
            player.seekBy(-3_000_000_000L)
            Thread.sleep(40)
        }
        prev = -1L
        repeat(20) {
            Thread.sleep(50)
            val ms = player.positionNanos() / 1_000_000
            val delta = if (prev < 0) 0 else ms - prev
            println("t=${it * 50}ms pos=${ms}ms d=${delta}ms state=${player.state::class.simpleName}")
            prev = ms
        }
    }
}
