package dev.hivens.skinema.demo

import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.player.VideoPlayer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Headless soak: loops a video through the full decode/pace/mailbox pipeline
 * for the given number of minutes, polling like a renderer would. The adoption
 * bar (ROADMAP.md, milestones) wants a long run whose RSS does not grow, and
 * this is the tool that measures it.
 *
 *   ./gradlew :skinema-demo:soak -Pvideo=<file> [-Pminutes=N] [-PreadAhead=N]
 *                                 [-PsoakAudio=true] [-Phardware=AUTO]
 *                                 [-Pheap=256m]
 *
 * ## Why it reports a floor and not just a reading
 *
 * An instantaneous RSS is the wrong number to judge growth by, and reporting
 * only that made an hour-long run look like a leak. Heap climbs between
 * collections and RSS follows it, so a series of once-a-minute readings is a
 * sawtooth whose teeth depend entirely on where the collector happened to be
 * when each sample landed. Measured: one run drifted 309 -> 345 MB across its
 * second half with heap climbing 63 -> 103 over the same span, and a single
 * collection in the whole hour.
 *
 * What answers the question is the LOW-WATER MARK -- what the process falls
 * back to once a collection has run. A floor that stays put across a long run
 * is what "RSS does not grow" means; a floor that climbs is a leak, whatever
 * the peaks do. So samples are taken every couple of seconds and each report
 * carries the minimum seen since the last one, and the run ends by comparing
 * the floor of its first half against the floor of its second.
 *
 * `-Pheap` exists for the same reason: capping the heap makes collections
 * frequent, so the floor is sampled many times rather than once or twice.
 */
fun main(args: Array<String>) {
    val video = Path.of(requireNotNull(args.firstOrNull()) { "usage: soak <video> [minutes]" })
    val minutes = args.getOrNull(1)?.toLong() ?: 10L
    val readAhead = System.getProperty("skinema.demo.readAhead")?.toInt() ?: 1
    // Off by default, because the bar this tool measures is the plain looping
    // path. Turned on it covers what that path never touches: the audio and
    // watchdog threads, a device handle held for the whole run, and frames
    // downloaded off the GPU rather than decoded in place.
    val audio = System.getProperty("skinema.demo.soakAudio") == "true"
    val hardware = System.getProperty("skinema.demo.hardware")
        ?.let { HwAccel.valueOf(it.uppercase()) }
        ?: HwAccel.OFF

    println("soak: minutes=$minutes readAhead=$readAhead audio=$audio hardware=$hardware")
    VideoPlayer(video, loop = true, audio = audio, readAheadFrames = readAhead, hardware = hardware).use { player ->
        val started = System.nanoTime()
        val deadline = started + minutes * 60_000_000_000L
        val halfway = started + minutes * 30_000_000_000L
        var frames = 0L
        var nextSample = started
        var nextReport = started
        // Floors: per report window, and one for each half of the run. The
        // halves are what the verdict is read from.
        var windowRss = Long.MAX_VALUE
        var windowHeap = Long.MAX_VALUE
        var firstHalfRss = Long.MAX_VALUE
        var secondHalfRss = Long.MAX_VALUE

        while (System.nanoTime() < deadline) {
            val state = player.state
            if (state is VideoPlayer.State.Failed) error("player failed during soak: ${state.cause}")
            if (player.acquireFrame() != null) frames++

            val now = System.nanoTime()
            if (now >= nextSample) {
                rssMb()?.let { rss ->
                    if (rss < windowRss) windowRss = rss
                    // The first samples are the process still warming up, and a
                    // floor taken from them would flatter every later one. The
                    // halves start after the first minute for that reason.
                    if (now - started > 60_000_000_000L) {
                        if (now < halfway) {
                            if (rss < firstHalfRss) firstHalfRss = rss
                        } else if (rss < secondHalfRss) {
                            secondHalfRss = rss
                        }
                    }
                }
                heapMb().let { if (it < windowHeap) windowHeap = it }
                nextSample = now + SAMPLE_NANOS
            }

            if (now >= nextReport) {
                println(
                    "frames=%d rssMb=%s rssFloorMb=%s heapMb=%d heapFloorMb=%s".format(
                        frames,
                        rssMb() ?: "n/a",
                        if (windowRss == Long.MAX_VALUE) "n/a" else windowRss,
                        heapMb(),
                        if (windowHeap == Long.MAX_VALUE) "n/a" else windowHeap,
                    ),
                )
                windowRss = Long.MAX_VALUE
                windowHeap = Long.MAX_VALUE
                nextReport = now + REPORT_NANOS
            }
            Thread.sleep(5)
        }

        println(
            "soak done: $frames frames over $minutes min, audio=$audio hardware=$hardware" +
                " hardwareActive=${player.hardwareActive} final rssMb=${rssMb() ?: "n/a"}",
        )
        // The verdict, stated rather than left to whoever reads the series. A
        // rising floor is the leak; peaks are the collector's business.
        if (firstHalfRss != Long.MAX_VALUE && secondHalfRss != Long.MAX_VALUE) {
            val drift = secondHalfRss - firstHalfRss
            println(
                "soak floor: first half ${firstHalfRss}MB, second half ${secondHalfRss}MB," +
                    " drift ${if (drift >= 0) "+" else ""}${drift}MB",
            )
        } else {
            println("soak floor: not enough samples past warm-up to compare halves")
        }
        check(frames > 0) { "soak decoded nothing" }
    }
}

/** Often enough that a collection cannot hide between two samples. */
private const val SAMPLE_NANOS = 2_000_000_000L

private const val REPORT_NANOS = 60_000_000_000L

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
