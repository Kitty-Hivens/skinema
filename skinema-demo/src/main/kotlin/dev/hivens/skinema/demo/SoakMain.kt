package dev.hivens.skinema.demo

import dev.hivens.skinema.libav.HwAccel
import dev.hivens.skinema.player.VideoPlayer
import dev.hivens.skinema.skiko.VideoFrameImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless soak: loops a video through the full decode/pace/mailbox pipeline
 * for the given number of minutes, polling like a renderer would. The adoption
 * bar (ROADMAP.md, milestones) wants a long run whose RSS does not grow, and
 * this is the tool that measures it.
 *
 *   ./gradlew :skinema-demo:soak -Pvideo=<file> [-Pminutes=N] [-PreadAhead=N]
 *                                 [-PsoakAudio=true] [-Phardware=AUTO]
 *                                 [-Pheap=256m] [-PsoakImages=true]
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
 * carries the minimum seen since the last one, and the run ends by reporting
 * the floor of each third -- the first being warm-up, and the verdict read
 * from the last two against each other.
 *
 * `-Pheap` exists for the same reason: capping the heap makes collections
 * frequent, so the floor is sampled many times rather than once or twice.
 *
 * ## Why -PsoakImages exists
 *
 * Without it this measures the pipeline and stops at the mailbox: the only
 * consumer-side call is [VideoPlayer.acquireFrame], so nothing here ever built
 * a Skia image. That left the component whose entire job is holding native
 * memory outside the run that exists to prove native memory does not grow --
 * and a heap profiler cannot see what a Skia image holds, which is the whole
 * reason [VideoFrameImage] is shaped the way it is.
 *
 * Turned on, the run carries a real [VideoFrameImage] in the shape a consumer
 * uses it: this loop rasters, because it is the side holding the frames, and a
 * second thread draws -- reclaiming and reading at a screen's cadence. That is
 * the Compose surface's split with the roles named the other way round, and it
 * is what puts the borrow across a thread boundary rather than leaving it a
 * single-threaded exercise.
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
    // Off by default so a run stays comparable with every earlier one; on, it
    // adds the half the mailbox is not: eight megabytes of Skia raster per
    // frame, held and freed by a class no heap profiler can account for.
    val images = System.getProperty("skinema.demo.soakImages") == "true"
    // Gain, not routing: at zero the audio path is entirely intact -- its
    // thread, a real device handle held for the whole run, the watchdog and the
    // clock that masters pacing -- and only the amplitude on the line is gone.
    // Without it the sound run is two hours of audible looping next to whoever
    // started it, which is how a pre-release gate quietly stops being run.
    val volume = System.getProperty("skinema.demo.soakVolume")?.toFloat() ?: 1f

    println(
        "soak: minutes=$minutes readAhead=$readAhead audio=$audio hardware=$hardware" +
            " images=$images volume=$volume",
    )
    val frameImage = if (images) VideoFrameImage() else null
    VideoPlayer(
        video,
        loop = true,
        audio = audio,
        readAheadFrames = readAhead,
        hardware = hardware,
        volume = volume,
    ).use { player ->
        val started = System.nanoTime()
        val deadline = started + minutes * 60_000_000_000L
        val firstThird = started + minutes * 20_000_000_000L
        val secondThird = started + minutes * 40_000_000_000L
        var frames = 0L
        var nextSample = started
        var nextReport = started
        // Floors: one per report window, plus the three the verdict uses.
        var windowRss = Long.MAX_VALUE
        var windowHeap = Long.MAX_VALUE
        // Thirds, not halves, and the reason is measured: on this machine the
        // floor climbs for the first ten minutes of a thirty-minute run --
        // JIT, native pools, GPU surfaces -- and then sits still. Comparing
        // halves puts that warm-up entirely inside the first one and reports
        // its climb as drift: a run whose floor was flat at 370 MB for its
        // whole last third came out as "+68 MB". Three windows show the shape
        // instead of hiding it in one number, and the verdict reads the last
        // two against each other.
        val thirds = LongArray(3) { Long.MAX_VALUE }

        // The drawing side, when images are on: reclaim and read at a screen's
        // cadence, which is what makes the borrow cross a thread boundary. It
        // reads the image and touches it, because a reference that survives
        // while its pixels do not is the same defect one step later.
        val stop = AtomicBoolean(false)
        val drawer = frameImage?.let { holder ->
            Thread({
                var drawn = 0L
                while (!stop.get()) {
                    holder.reclaim()
                    holder.image?.let { if (it.width > 0) drawn++ }
                    Thread.sleep(16)
                }
                println("soak drawer: $drawn draws")
            }, "soak-draw").apply { isDaemon = true; start() }
        }

        while (System.nanoTime() < deadline) {
            val state = player.state
            if (state is VideoPlayer.State.Failed) error("player failed during soak: ${state.cause}")
            player.acquireFrame()?.let { slot ->
                frames++
                frameImage?.update(slot.width, slot.height, slot.rgba)
            }

            val now = System.nanoTime()
            if (now >= nextSample) {
                rssMb()?.let { rss ->
                    if (rss < windowRss) windowRss = rss
                    val third = if (now < firstThird) 0 else if (now < secondThird) 1 else 2
                    if (rss < thirds[third]) thirds[third] = rss
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

        // Joined before the holder is closed, so the teardown cannot free a
        // session out from under a draw in flight -- the rule the Compose
        // surface keeps for the same pair of threads.
        stop.set(true)
        drawer?.join(2_000)
        frameImage?.let { println("soak images: pending=${it.pending} at teardown"); it.close() }

        println(
            "soak done: $frames frames over $minutes min, audio=$audio hardware=$hardware" +
                " images=$images hardwareActive=${player.hardwareActive} final rssMb=${rssMb() ?: "n/a"}",
        )
        // The verdict, stated rather than left to whoever reads the series. A
        // rising floor is the leak; peaks are the collector's business.
        if (thirds.none { it == Long.MAX_VALUE }) {
            // The verdict is the last third against the middle one. The first
            // is warm-up and is printed rather than judged.
            val drift = thirds[2] - thirds[1]
            println(
                "soak floor by third: ${thirds[0]}MB (warm-up), ${thirds[1]}MB, ${thirds[2]}MB" +
                    " -- settled drift ${if (drift >= 0) "+" else ""}${drift}MB",
            )
        } else {
            println("soak floor: not enough samples to fill three windows")
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
