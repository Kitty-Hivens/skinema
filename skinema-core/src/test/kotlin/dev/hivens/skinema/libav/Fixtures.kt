package dev.hivens.skinema.libav

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration-test fixtures: tiny videos generated at test time by the
 * ffmpeg CLI, never committed as binaries (ROADMAP.md section 9). Tests
 * calling [assumeDecodeEnvironment] skip -- not fail -- on machines
 * without the CLI or loadable pinned libav libraries; CI provides both.
 */
object Fixtures {

    private val ffmpegOnPath: Boolean by lazy {
        runCatching {
            val proc = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
            proc.inputStream.readAllBytes()
            proc.waitFor() == 0
        }.getOrDefault(false)
    }

    private val libavLoadable: Boolean by lazy {
        runCatching { Libav.versions }.isSuccess
    }

    // CI exports SKINEMA_REQUIRE_DECODE so a broken runner environment
    // (missing CLI, unloadable libraries) fails the build loudly instead
    // of silently skipping every integration test and faking green.
    private val strict = System.getenv("SKINEMA_REQUIRE_DECODE") != null

    fun assumeDecodeEnvironment() {
        if (strict) {
            check(ffmpegOnPath) { "SKINEMA_REQUIRE_DECODE is set but the ffmpeg CLI is not on PATH" }
            check(libavLoadable) { "SKINEMA_REQUIRE_DECODE is set but the pinned libav* did not load" }
            return
        }
        assumeTrue(ffmpegOnPath, "ffmpeg CLI not on PATH -- skipping integration test")
        assumeTrue(libavLoadable, "pinned libav* not loadable -- skipping integration test")
    }

    private val encoders: Set<String> by lazy {
        runCatching {
            val proc = ProcessBuilder("ffmpeg", "-hide_banner", "-encoders").redirectErrorStream(true).start()
            val names = proc.inputStream.readAllBytes().decodeToString()
                .lineSequence()
                .mapNotNull { line -> Regex("^ [A-Z.]{6} (\\S+)").find(line)?.groupValues?.get(1) }
                .toSet()
            proc.waitFor()
            names
        }.getOrDefault(emptySet())
    }

    /**
     * Skips when the fixture CLI lacks [encoder]. Deliberately NOT
     * escalated by SKINEMA_REQUIRE_DECODE: what the runner's CLI can
     * encode is the environment's business (brew ships ffmpeg without
     * libaom/libwebp), not part of skinema's decode contract.
     */
    fun assumeEncoder(encoder: String) {
        assumeTrue(encoder in encoders, "CLI lacks encoder $encoder -- fixture impossible, skipping")
    }

    /**
     * Runs `ffmpeg <args> <output>` and returns [output]. Fixture codecs
     * mirror the shipped decode whitelist (h264 via libx264, vp9 via
     * libvpx) -- tests must exercise what the trimmed builds carry, so CI
     * runners need a full-build CLI (apt/brew/choco), not an LGPL one.
     */
    fun generate(output: Path, vararg args: String): Path {
        val cmd = listOf("ffmpeg", "-y", "-hide_banner", "-loglevel", "error") + args + output.toString()
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = proc.inputStream.readAllBytes().decodeToString()
        check(proc.waitFor() == 0) { "ffmpeg failed for ${output.fileName}: $log" }
        check(Files.size(output) > 0) { "ffmpeg produced an empty ${output.fileName}" }
        return output
    }
}
