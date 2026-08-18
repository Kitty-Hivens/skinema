package dev.hivens.skinema.scratch

import dev.hivens.skinema.libav.Fixtures
import dev.hivens.skinema.player.VideoPlayer
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Black-box probes of the documented VideoPlayer contract. */
class ContractProbeTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        Fixtures.assumeDecodeEnvironment()
        tmp = Files.createTempDirectory("skinema-scratch")
    }

    @AfterTest
    fun tearDown() {
        if (::tmp.isInitialized) {
            Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    private fun awaitTrue(deadlineMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun clip(name: String, seconds: String = "1", fps: String = "10", size: String = "64x64"): Path =
        Fixtures.generate(
            tmp.resolve(name),
            "-f", "lavfi", "-i", "testsrc=size=$size:rate=$fps",
            "-t", seconds, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "5",
        )

    private fun skinemaCpuNanos(): Long {
        val mx = ManagementFactory.getThreadMXBean()
        return Thread.getAllStackTraces().keys
            .filter { it.name.startsWith("skinema-") }
            .sumOf { mx.getThreadCpuTime(it.threadId()).coerceAtLeast(0L) }
    }

    // -- 1. acquireFrame ownership window -------------------------------------
    // "The consumer owns the returned slot until its next acquireFrame call;
    //  the player never writes into it during that window."
    @Test
    fun acquiredSlotIsNotWrittenUntilNextAcquire() {
        val file = clip("own.mp4", seconds = "3", fps = "30")
        VideoPlayer(file, loop = true).use { p ->
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing }, "never played")
            var slot: VideoPlayer.FrameSlot? = null
            assertTrue(awaitTrue { p.acquireFrame()?.also { slot = it } != null }, "no frame ever")
            val held = slot!!
            val array = held.rgba
            val snapshot = array.copyOf()
            val pts = held.ptsNanos
            Thread.sleep(1_500)
            val sameArray = held.rgba === array
            val sameBytes = array.contentEquals(snapshot)
            val samePts = held.ptsNanos == pts
            println(
                "OWNERSHIP: sameArrayIdentity=$sameArray sameBytesInHeldArray=$sameBytes " +
                    "samePts=$samePts heldPts=$pts nowPts=${held.ptsNanos}",
            )
            assertTrue(sameArray && sameBytes && samePts, "the player wrote into a slot the consumer still owns")
        }
    }

    // -- 2. setRate clamp ------------------------------------------------------
    // "Playback speed, pitch preserved (atempo), clamped to [0.5, 4.0]"
    @Test
    fun rateIsClampedToTheDocumentedRange() {
        val file = clip("rate.mp4", seconds = "5", fps = "30")
        VideoPlayer(file, loop = true).use { p ->
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing }, "never played")
            p.setRate(Float.NaN)
            Thread.sleep(300)
            val r = p.rate
            val posA = p.positionNanos()
            Thread.sleep(500)
            val posB = p.positionNanos()
            println("RATE: after setRate(NaN) rate=$r positions=$posA -> $posB state=${p.state}")
            assertTrue(r in 0.5f..4.0f, "rate=$r is outside the documented [0.5, 4.0] clamp")
        }
    }

    // -- 3. a file with no decodable frames -----------------------------------
    // "never a thrown constructor, never a half-decoded garbage frame, never a hang"
    @Test
    fun zeroFrameFileLoopingDoesNotBurnACore() {
        val zero = Fixtures.generate(
            tmp.resolve("zero.mp4"),
            "-f", "lavfi", "-i", "color=c=blue:s=64x64:r=10",
            "-frames:v", "0", "-c:v", "libx264", "-pix_fmt", "yuv420p",
        )
        VideoPlayer(zero, loop = true).use { p ->
            awaitTrue(3_000) { p.state !is VideoPlayer.State.Opening }
            Thread.sleep(200)
            val before = skinemaCpuNanos()
            val wall = System.nanoTime()
            Thread.sleep(2_000)
            val cpu = skinemaCpuNanos() - before
            val elapsed = System.nanoTime() - wall
            println(
                "ZERO-FRAME loop=true: state=${p.state} duration=${p.durationNanos} pos=${p.positionNanos()} " +
                    "cpu=${cpu / 1_000_000}ms over ${elapsed / 1_000_000}ms wall (${100.0 * cpu / elapsed}%)",
            )
            assertTrue(cpu < elapsed / 4, "the player spun on a frameless file: ${100.0 * cpu / elapsed}% of a core")
        }
    }

    @Test
    fun zeroFrameFileNonLoopingEnds() {
        val zero = Fixtures.generate(
            tmp.resolve("zero2.mp4"),
            "-f", "lavfi", "-i", "color=c=blue:s=64x64:r=10",
            "-frames:v", "0", "-c:v", "libx264", "-pix_fmt", "yuv420p",
        )
        VideoPlayer(zero, loop = false).use { p ->
            val ended = awaitTrue(5_000) {
                p.state is VideoPlayer.State.Ended || p.state is VideoPlayer.State.Failed
            }
            println("ZERO-FRAME loop=false: settled=$ended state=${p.state} duration=${p.durationNanos}")
            assertTrue(ended, "a file with no frames neither ended nor failed: ${p.state}")
        }
    }

    // -- 4. stepForward at the end --------------------------------------------
    // "Both leave the player paused on the stepped frame"
    @Test
    fun stepForwardFromEndedLeavesThePlayerPaused() {
        val file = clip("step.mp4", seconds = "0.5", fps = "10")
        VideoPlayer(file, loop = false).use { p ->
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Ended }, "never ended: ${p.state}")
            p.stepForward()
            Thread.sleep(500)
            println("STEP-AT-END: state=${p.state} pos=${p.positionNanos()} duration=${p.durationNanos}")
            assertTrue(p.state is VideoPlayer.State.Paused, "stepForward left state=${p.state}, not Paused")
        }
    }

    // -- 5. close() ------------------------------------------------------------
    // "After close() the state is Closed."
    @Test
    fun closeLeavesTheStateClosedEvenAfterAFailedOpen() {
        val junk = tmp.resolve("junk.mp4")
        Files.write(junk, ByteArray(4096) { (it % 251).toByte() })
        val p = VideoPlayer(junk, loop = true)
        assertTrue(awaitTrue { p.state is VideoPlayer.State.Failed }, "a junk file did not fail: ${p.state}")
        val failed = p.state
        p.close()
        println("CLOSE-AFTER-FAIL: stateBefore=$failed stateAfterClose=${p.state}")
        assertTrue(p.state is VideoPlayer.State.Closed, "after close() the state is ${p.state}, not Closed")
    }

    @Test
    fun closeLeavesTheStateClosedOnAHealthyPlayer() {
        val file = clip("close.mp4", seconds = "3", fps = "30")
        val p = VideoPlayer(file, loop = true)
        assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing }, "never played")
        val t0 = System.nanoTime()
        p.close()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("CLOSE-HEALTHY: state=${p.state} closeTookMs=$ms")
        assertTrue(p.state is VideoPlayer.State.Closed, "after close() the state is ${p.state}")
        assertTrue(ms <= 5_000, "close() blocked ${ms}ms, past its documented five seconds")
    }

    // -- 6. Ended, position and revival ---------------------------------------
    @Test
    fun endedLandsOnDurationAndASeekRevives() {
        val file = clip("end.mp4", seconds = "1", fps = "30")
        VideoPlayer(file, loop = false).use { p ->
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Ended }, "never ended: ${p.state}")
            val dur = p.durationNanos
            val atEnd = p.positionNanos()
            Thread.sleep(400)
            val stillAtEnd = p.positionNanos()
            p.seek(0)
            val revived = awaitTrue(5_000) { p.state is VideoPlayer.State.Playing }
            Thread.sleep(300)
            println(
                "ENDED: duration=$dur posAtEnd=$atEnd posLater=$stillAtEnd " +
                    "revivedToPlaying=$revived stateAfterSeek=${p.state} posAfterSeek=${p.positionNanos()}",
            )
            assertTrue(stillAtEnd == atEnd, "the timeline kept moving after Ended: $atEnd -> $stillAtEnd")
            assertTrue(revived, "seek(0) did not revive an Ended player: ${p.state}")
        }
    }

    // -- 7. the decode pump and a consumer that never polls --------------------
    // "A consumer that stops polling (a hidden window) simply stops the decode
    //  pump until it polls again."
    @Test
    fun aConsumerThatNeverPollsStopsTheDecodePump() {
        val file = clip("pump.mp4", seconds = "10", fps = "30", size = "640x480")
        VideoPlayer(file, loop = true).use { p ->
            assertTrue(awaitTrue { p.state is VideoPlayer.State.Playing }, "never played")
            // One poll, then never again: the "hidden window" case.
            p.acquireFrame()
            Thread.sleep(500)
            val before = skinemaCpuNanos()
            val wall = System.nanoTime()
            Thread.sleep(3_000)
            val cpu = skinemaCpuNanos() - before
            val elapsed = System.nanoTime() - wall
            val posMoved = p.positionNanos()
            println(
                "UNPOLLED PUMP: cpu=${cpu / 1_000_000}ms over ${elapsed / 1_000_000}ms wall " +
                    "(${100.0 * cpu / elapsed}%) position=$posMoved state=${p.state}",
            )
            assertTrue(cpu < elapsed / 20, "the decode pump kept running unpolled: ${100.0 * cpu / elapsed}% of a core")
        }
    }
}
