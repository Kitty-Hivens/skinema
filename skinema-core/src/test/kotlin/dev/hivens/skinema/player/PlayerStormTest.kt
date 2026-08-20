package dev.hivens.skinema.player

import dev.hivens.skinema.audio.PacedPcmSink
import dev.hivens.skinema.libav.Fixtures
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a hand on a timeline actually does. Every burst this player handles is
 * proven on its own -- a run of seeks coalescing to one landing, a run of
 * backsteps moving one frame per press -- and each of those tests issues one
 * KIND of command. The failures this player has actually had were freezes,
 * and every one of them came from two mechanisms meeting: a seek arriving
 * inside a lap wrap, a pause landing during a landing, a rate change over a
 * buffered tail. Nothing exercised the mixture.
 *
 * So this issues everything at once, from a seeded sequence so a failure can
 * be run again, and asserts what must survive any interleaving: the player
 * answers, plays and closes. Not that it is anywhere in particular -- where a
 * storm leaves the playhead is nobody's contract.
 *
 * What it does NOT do, measured rather than assumed. Four historical defects
 * were seeded back in and this passed on every one: the landing handshake
 * dropped from a seek, a seek inside a lap wrap being undone by it, the audio
 * side never admitting its seeks landed, and a seek no longer reviving an
 * ended player. The reason is the same each time and it is structural -- under
 * a storm the NEXT command repairs what the last one broke, so a defect that
 * any press papers over never becomes observable, and the storm cannot end on
 * a known state without a recovery sequence that repairs the rest. A timeline
 * stall counter measured 59 ms on one of those against 4 ms healthy: a real
 * difference, and far too narrow to hold a runner to.
 *
 * Which leaves this covering the class those four are not: a player that stops
 * answering at all. Every mixture-shaped defect this suite has actually caught
 * was caught by a test that names its scenario, and that is where the next one
 * should go too.
 */
class PlayerStormTest {

    private val dir: Path = Files.createTempDirectory("skinema-storm-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun av(name: String): Path = Fixtures.generate(
        dir.resolve(name),
        "-f", "lavfi", "-i", "testsrc2=size=96x64:rate=25",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
        "-map", "0:v", "-map", "1:a", "-t", "2",
        // Two seconds, looping: the storm has to CROSS the lap wrap, over
        // and over, because that is where the mechanisms meet -- a seek
        // arriving inside a wrap, a pause landing on one. A ten-second
        // fixture under a four-second storm never reaches the loop point at
        // all, and a storm that never reaches it proves nothing about it.
        // Keyframes every half second, so an exact seek still costs a real
        // decode-forward run and the storm lands inside one.
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-g", "12",
        "-c:a", "flac",
    )

    /** Every command the player takes, weighted the way a hand on a scrubber is. */
    private fun stormOnce(player: VideoPlayer, rng: Random) {
        when (rng.nextInt(10)) {
            0, 1 -> player.pause()
            2, 3 -> player.resume()
            4, 5 -> player.seek(rng.nextLong(0, 2_000) * 1_000_000L, exact = rng.nextBoolean())
            6 -> player.seekBy((rng.nextLong(-1_500, 1_500)) * 1_000_000L)
            7 -> player.setRate(0.5f + rng.nextFloat() * 3.5f)
            8 -> if (rng.nextBoolean()) player.stepForward() else player.stepBackward()
            else -> player.setVolume(rng.nextFloat())
        }
    }

    @Test
    fun `a storm of mixed commands leaves a player that still plays`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = PacedPcmSink(bufferFrames = 8_820)
        val failure = AtomicReference<VideoPlayer.State.Failed?>(null)
        val longestStall = java.util.concurrent.atomic.AtomicLong(0)
        VideoPlayer(av("storm.mkv"), loop = true, audio = true, sink = sink, readAheadFrames = 3).use { player ->
            try {
                assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")

                // Two threads, because a single one serialises what a real
                // consumer does not: the render loop polls while the UI
                // thread issues commands.
                val stop = System.currentTimeMillis() + 4_000
                val commander = thread(name = "storm-commands") {
                    val rng = Random(20260818)
                    while (System.currentTimeMillis() < stop) {
                        stormOnce(player, rng)
                        Thread.sleep(rng.nextLong(1, 25))
                    }
                }
                val watcher = thread(name = "storm-render") {
                    var lastPos = -1L
                    var lastPts = -1L
                    var lastMoveWall = System.nanoTime()
                    while (System.currentTimeMillis() < stop) {
                        val framePts = player.acquireFrame()?.ptsNanos ?: lastPts
                        // No "the picture stood still while the timeline ran"
                        // counter here, though it is the obvious next one to
                        // want. Measured, it reads 64ms on healthy code and
                        // the same under two seeded defects: an exact seek's
                        // decode-forward run IS the timeline running with
                        // nothing new to publish, so the number describes
                        // ordinary work and separates nothing.
                        player.acquireSubtitles()
                        val st = player.state
                        (st as? VideoPlayer.State.Failed)?.let { failure.compareAndSet(null, it) }
                        val pos = player.positionNanos()
                        val now = System.nanoTime()
                        val playing = st is VideoPlayer.State.Playing
                        val timelineMoved = pos != lastPos
                        lastPts = framePts

                        // The timeline standing while the player says it is playing.
                        if (timelineMoved || !playing) {
                            lastMoveWall = now
                        } else if (now - lastMoveWall > longestStall.get()) {
                            longestStall.set(now - lastMoveWall)
                        }
                        lastPos = pos
                        Thread.sleep(2)
                    }
                }
                commander.join(10_000)
                watcher.join(10_000)
                assertTrue(!commander.isAlive && !watcher.isAlive, "the storm threads must finish")
                failure.get()?.let { throw AssertionError("the player failed mid-storm", it.cause) }
                // A wedge, not a hiccup. Under a storm every command repairs
                // something, so a defect that the NEXT press papers over never
                // grows past a few tens of milliseconds -- the landing
                // handshake dropped from handleSeek measures 59ms here
                // against 4ms healthy, which is a real difference and far too
                // narrow to hold a CI runner to. What this bound catches is
                // the timeline that stops and does not come back, which is
                // what every freeze in this player's history actually was.
                val stalledMs = longestStall.get() / 1_000_000
                assertTrue(stalledMs < 500, "the timeline stopped for ${stalledMs}ms while the player said Playing")

                // The storm is over. Put the player somewhere known and ask
                // it to work: this is the whole assertion, because every
                // freeze this player has had looked exactly like this and
                // did not come back.
                player.seek(500_000_000L)
                player.setRate(1f)
                player.resume()
                assertTrue(
                    awaitTrue(8_000) { player.state is VideoPlayer.State.Playing },
                    "the player must come back to Playing, state=${player.state}",
                )
                // Motion, counted -- NOT the two endpoints compared. This
                // player loops on a two-second file, so a reading taken after
                // a window this long is very often SMALLER than the one before
                // it, and the timeline is running perfectly.
                var frames = 0
                var moves = 0
                var last = player.positionNanos()
                val until = System.currentTimeMillis() + 1_500
                while (System.currentTimeMillis() < until) {
                    if (player.acquireFrame() != null) frames++
                    val now = player.positionNanos()
                    if (now != last) moves++
                    last = now
                    Thread.sleep(5)
                }
                assertTrue(frames > 5, "the picture must flow again, saw $frames frames")
                assertTrue(moves > 10, "the timeline must run again, moved $moves times in 1.5s")
                assertIs<VideoPlayer.State.Playing>(player.state, "and stay playing")
            } finally {
                sink.release()
            }
        }
    }

    /**
     * The narrowest of the mixtures, on its own because it is the one a
     * consumer reaches by accident: a button pressed faster than the decode
     * thread drains its queue.
     */
    @Test
    fun `pause and resume hammered at the queue still settles`() {
        Fixtures.assumeDecodeEnvironment()
        val sink = PacedPcmSink(bufferFrames = 8_820)
        VideoPlayer(av("hammer.mkv"), loop = true, audio = true, sink = sink, readAheadFrames = 2).use { player ->
            try {
                assertTrue(awaitTrue { player.acquireFrame() != null }, "playback must start")
                repeat(200) {
                    player.pause()
                    player.resume()
                }
                // An even number of pairs ending on resume: the player owes
                // itself nothing, and must arrive at Playing rather than at
                // whichever of the two the queue happened to stop on.
                assertTrue(
                    awaitTrue(8_000) { player.state is VideoPlayer.State.Playing },
                    "the player must settle on Playing, state=${player.state}",
                )
                val before = player.positionNanos()
                assertTrue(
                    awaitTrue(4_000) { player.acquireFrame(); player.positionNanos() > before + 100_000_000L },
                    "the timeline must run after the hammering, stuck at ${player.positionNanos() / 1_000_000}ms",
                )

                // And the other parity: ending on pause must actually stop.
                repeat(200) {
                    player.resume()
                    player.pause()
                }
                assertTrue(
                    awaitTrue(8_000) { player.state is VideoPlayer.State.Paused },
                    "ending on pause must leave it paused, state=${player.state}",
                )
                val frozen = player.positionNanos()
                Thread.sleep(300)
                assertTrue(
                    player.positionNanos() - frozen < 100_000_000L,
                    "a paused player's timeline must stand, moved ${(player.positionNanos() - frozen) / 1_000_000}ms",
                )
            } finally {
                sink.release()
            }
        }
    }
}
