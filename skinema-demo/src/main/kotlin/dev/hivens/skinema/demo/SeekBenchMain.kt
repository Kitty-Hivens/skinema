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

        // What the demo's buttons actually feel like: wall time from seek()
        // to the landing frame being acquirable. The clock samples above
        // cannot see publish latency.
        println("== seek-to-frame latency: alternating far targets ==")
        val latencies = mutableListOf<Long>()
        val targets = (0 until 24).map { i ->
            if (i % 2 == 0) (5_000 + i * 2_100).toLong() * 1_000_000L
            else (48_000 - i * 1_700).toLong() * 1_000_000L
        }
        for (target in targets) {
            while (player.acquireFrame() != null) Unit
            val t0 = System.nanoTime()
            player.seek(target)
            val deadline = t0 + 5_000_000_000L
            var landedAt = -1L
            while (System.nanoTime() < deadline) {
                val f = player.acquireFrame()
                if (f != null && f.ptsNanos in target..(target + 500_000_000L)) {
                    landedAt = System.nanoTime()
                    break
                }
                Thread.sleep(1)
            }
            val ms = if (landedAt < 0) -1 else (landedAt - t0) / 1_000_000
            latencies += ms
            println("seek=${target / 1_000_000}ms latency=${ms}ms")
            Thread.sleep(60)
        }
        val ok = latencies.filter { it >= 0 }.sorted()
        if (ok.isNotEmpty()) {
            println(
                "landed=${ok.size}/${latencies.size} median=${ok[ok.size / 2]}ms " +
                    "p90=${ok[ok.size * 9 / 10]}ms max=${ok.last()}ms",
            )
        }
    }
}
