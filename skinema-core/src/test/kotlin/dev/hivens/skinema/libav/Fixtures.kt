package dev.hivens.skinema.libav

import dev.hivens.skinema.ass.Ass
import dev.hivens.skinema.webp.Webp
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
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

    // CI lists the capabilities it holds mandatory in
    // SKINEMA_REQUIRE_CAPS (a comma list, e.g. "decode,subs,webp"). A
    // required capability that fails to load is a loud build failure,
    // not a silent skip: "genuinely absent" and "present but broken in
    // the bundle" both reduce to a skip otherwise, and a skip reads as
    // green -- a broken bundle must never fake green. A capability not
    // on the list keeps dev-box behaviour: skip when it cannot load.
    val requiredCaps: Set<String> =
        System.getenv("SKINEMA_REQUIRE_CAPS")
            .orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun requires(cap: String): Boolean = cap in requiredCaps

    /** Known capability names; [CapabilitiesTest] rejects anything else. */
    internal val knownCaps = setOf("decode", "subs", "webp", "encode")

    /** Pure load probe per capability -- no fixtures, no transcode. */
    internal fun capLoads(cap: String): Boolean = when (cap) {
        "decode" -> ffmpegOnPath && libavLoadable
        // libass renders, but the bundle must also DECODE subtitles -- and
        // the loader falls back to a system libass (apt's ffmpeg drags one
        // in), so Ass.available alone reads true on a core bundle that
        // carries no subtitle decoders of its own. The subrip decoder is
        // the bundle's own subtitle tell.
        "subs" -> Ass.available && libavHasDecoder("subrip")
        "webp" -> Webp.available
        // The full tier always carries x264 (mac/win keep enc-h264 even
        // without x265, #22), so libx264 is the encode path's load probe.
        "encode" -> libavHasEncoder("libx264")
        else -> error("unknown capability '$cap'")
    }

    fun assumeDecodeEnvironment() {
        if (requires("decode")) {
            check(ffmpegOnPath) { "SKINEMA_REQUIRE_CAPS lists 'decode' but the ffmpeg CLI is not on PATH" }
            check(libavLoadable) { "SKINEMA_REQUIRE_CAPS lists 'decode' but the pinned libav* did not load" }
            return
        }
        assumeTrue(ffmpegOnPath, "ffmpeg CLI not on PATH -- skipping integration test")
        assumeTrue(libavLoadable, "pinned libav* not loadable -- skipping integration test")
    }

    /**
     * Subtitles are an OPTIONAL capability -- the core tier ships without
     * them. Absence is a legal state unless SKINEMA_REQUIRE_CAPS lists
     * 'subs', which escalates it to a loud failure. Gated on [capLoads] so
     * a core bundle (libass resolvable from the system, but no subtitle
     * decoders of its own) skips these tests rather than failing them.
     */
    fun assumeSubtitleRendering() {
        if (requires("subs")) {
            check(capLoads("subs")) { "SKINEMA_REQUIRE_CAPS lists 'subs' but the bundle cannot decode and render subtitles" }
            return
        }
        assumeTrue(capLoads("subs"), "subtitle support absent in the bundle -- skipping subtitle test")
    }

    /**
     * Bitmap subtitles (PGS/VobSub) decode to pixels and need no libass --
     * only the bundle's subtitle decoder -- so they gate on the pgssub
     * decoder, not [capLoads]'s libass-plus-text-decoder pair. Same
     * required/optional split: a core bundle without it skips.
     */
    fun assumeBitmapSubtitles() {
        if (requires("subs")) {
            check(libavHasDecoder("pgssub")) { "SKINEMA_REQUIRE_CAPS lists 'subs' but the bundle has no PGS decoder" }
            return
        }
        assumeTrue(libavHasDecoder("pgssub"), "PGS subtitle decode absent in the bundle -- skipping bitmap subtitle test")
    }

    /**
     * Animated-webp decoding needs libwebpdemux, an OPTIONAL capability
     * (absent = the libav fallback). Listed as 'webp' in
     * SKINEMA_REQUIRE_CAPS its absence is a loud failure; otherwise a
     * skip. The webp suite reaches this only past [assumeEncoder], which
     * can skip first on a CLI without the libwebp encoder -- so the load
     * itself is held by [CapabilitiesTest], not only here.
     */
    fun assumeWebpDecoding() {
        if (requires("webp")) {
            check(Webp.available) { "SKINEMA_REQUIRE_CAPS lists 'webp' but libwebpdemux did not load" }
            return
        }
        assumeTrue(Webp.available, "libwebpdemux not loadable -- optional capability, skipping")
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
     * Skips when the fixture CLI lacks [encoder]. Deliberately NOT a
     * SKINEMA_REQUIRE_CAPS capability: what the runner's CLI can encode
     * is the environment's business (brew ships ffmpeg without
     * libaom/libwebp), not part of skinema's decode contract.
     */
    fun assumeEncoder(encoder: String) {
        assumeTrue(encoder in encoders, "CLI lacks encoder $encoder -- fixture impossible, skipping")
    }

    /**
     * Skips when the loaded libav has no encoder [name] (MediaWriter calls
     * avcodec_find_encoder_by_name, not the CLI). The decode-only shipped
     * bundle carries none, so encode tests skip there until the encode
     * milestone adds them; a dev box's full system FFmpeg runs them.
     */
    fun assumeLibraryEncoder(name: String) {
        assumeDecodeEnvironment()
        assumeTrue(libavHasEncoder(name), "libav has no encoder '$name' -- skipping encode test")
    }

    /** Whether the loaded libav exposes encoder [name] (avcodec_find_encoder_by_name). */
    private fun libavHasEncoder(name: String): Boolean = libavResolves(name, Libav::avcodecFindEncoderByName)

    /** Whether the loaded libav exposes decoder [name] (avcodec_find_decoder_by_name). */
    private fun libavHasDecoder(name: String): Boolean = libavResolves(name, Libav::avcodecFindDecoderByName)

    private fun libavResolves(name: String, find: (MemorySegment) -> MemorySegment): Boolean = runCatching {
        Arena.ofConfined().use { a -> find(a.allocateFrom(name)) != MemorySegment.NULL }
    }.getOrDefault(false)

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
