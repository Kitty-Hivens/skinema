package dev.hivens.skinema.demo

import dev.hivens.skinema.player.VideoPlayer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Headless soak: loops a video through the full decode/pace/mailbox
 * pipeline for the given number of minutes, polling like a renderer
 * would, and reports frames + RSS once a minute. The adoption bar
 * (ROADMAP.md, milestones) wants a long run with flat RSS; this is the
 * tool that measures it.
 *
 *   ./gradlew :skinema-demo:soak -Pvideo=<file> [-Pminutes=N]
 */
fun main(args: Array<String>) {
    val video = Path.of(requireNotNull(args.firstOrNull()) { "usage: soak <video> [minutes]" })
    val minutes = args.getOrNull(1)?.toLong() ?: 10L

    VideoPlayer(video, loop = true).use { player ->
        val deadline = System.nanoTime() + minutes * 60_000_000_000L
        var frames = 0L
        var nextReport = System.nanoTime()
        while (System.nanoTime() < deadline) {
            val state = player.state
            if (state is VideoPlayer.State.Failed) error("player failed during soak: ${state.cause}")
            if (player.acquireFrame() != null) frames++
            if (System.nanoTime() >= nextReport) {
                println("frames=%d rssMb=%s heapMb=%d".format(frames, rssMb() ?: "n/a", heapMb()))
                nextReport += 60_000_000_000L
            }
            Thread.sleep(5)
        }
        println("soak done: $frames frames over $minutes min, final rssMb=${rssMb() ?: "n/a"}")
        check(frames > 0) { "soak decoded nothing" }
    }
}

/** Resident set size from /proc on Linux; null elsewhere. */
private fun rssMb(): Long? {
    val status = Path.of("/proc/self/status")
    if (!Files.isReadable(status)) return null
    val line = Files.readAllLines(status).firstOrNull { it.startsWith("VmRSS:") } ?: return null
    return line.removePrefix("VmRSS:").trim().split(Regex("\\s+")).first().toLong() / 1024
}

private fun heapMb(): Long {
    val rt = Runtime.getRuntime()
    return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
}
