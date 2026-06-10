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

    fun assumeDecodeEnvironment() {
        assumeTrue(ffmpegOnPath, "ffmpeg CLI not on PATH -- skipping integration test")
        assumeTrue(libavLoadable, "pinned libav* not loadable -- skipping integration test")
    }

    /**
     * Runs `ffmpeg <args> <output>` and returns [output]. Encoder choices
     * in callers stick to what every ffmpeg CLI build carries (mpeg4,
     * libvpx) so fixture generation works on GPL and LGPL builds alike.
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
