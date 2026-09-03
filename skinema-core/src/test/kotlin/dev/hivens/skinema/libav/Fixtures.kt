package dev.hivens.skinema.libav

import dev.hivens.skinema.ass.Ass
import dev.hivens.skinema.audio.JavaSoundSink
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.foreign.Arena
import java.io.File
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
    internal val knownCaps =
        setOf(
            "decode", "subs", "webp", "dvbsub", "cea608", "encode", "formats", "audio",
            "encav1", "encopus",
        )

    /**
     * Pure load probe per capability -- no fixtures, no transcode.
     *
     * Every arm below reaches into [Libav], whose initializer loads the native
     * libraries, so on a machine carrying none the question "is this capability
     * available" answered with a thrown ExceptionInInitializerError instead of
     * with false -- and with a bare NoClassDefFoundError for every caller after
     * the first, since a class that failed to initialize stays failed. Tests
     * gated on an optional capability then FAILED where they were written to
     * skip. Asked first, this makes the whole set answer false together, which
     * is what "no libav here" means.
     */
    internal fun capLoads(cap: String): Boolean = if (!libavLoadable) false else when (cap) {
        "decode" -> ffmpegOnPath && libavLoadable
        // libass renders, but the bundle must also DECODE subtitles -- and
        // the loader falls back to a system libass (apt's ffmpeg drags one
        // in), so Ass.available alone reads true on a core bundle that
        // carries no subtitle decoders of its own. The subrip decoder is
        // the bundle's own subtitle tell.
        "subs" -> Ass.available && libavHasDecoder("subrip")
        // Animated WebP is FFmpeg's own decoder now; the still webp decoder
        // rides the base whitelist, so webp_anim is the tell for the feature.
        "webp" -> libavHasDecoder("webp_anim")
        // Its own name rather than a part of 'subs', because a bundle built
        // before it is a legal bundle: listing it under the subtitle contract
        // would turn "older than this decoder" into a failed run on every row
        // that holds subtitles mandatory, which is all of them.
        "dvbsub" -> libavHasDecoder("dvbsub")
        // Closed captions, and the name to probe is the DECODER's rather than
        // the whitelist entry's: the build asks for 'ccaption' and the decoder
        // it produces is called cc_dec. Its own capability for the same reason
        // dvbsub has one -- a bundle built before it is a legal bundle, and
        // folding it into the subtitle contract would fail every row that
        // holds subtitles mandatory.
        "cea608" -> libavHasDecoder("cc_dec")
        // The full tier always carries x264 (mac/win keep enc-h264 even
        // without x265, #22), so libx264 is the encode path's load probe.
        // It is the GPL tier's tell specifically, which is why the two BSD
        // encoders below get names of their own: they ride decode as well,
        // and a decode bundle has no libx264 to stand in for them.
        "encode" -> libavHasEncoder("libx264")
        "encav1" -> libavHasEncoder("libsvtav1")
        "encopus" -> libavHasEncoder("libopus")
        // The broad legacy/extended decode set (the formats feature): mpeg2
        // is its canonical member, present whenever the feature is on.
        "formats" -> libavHasDecoder("mpeg2video")
        // Not a property of the bundle but of the machine: whether a real
        // output line opens at all. Named here so a runner that is SUPPOSED
        // to have sound fails loudly instead of skipping its way to green.
        "audio" -> audioLineOpens
        else -> error("unknown capability '$cap'")
    }

    private val audioLineOpens: Boolean by lazy {
        runCatching {
            JavaSoundSink().use { it.open(48_000) }
            true
        }.getOrDefault(false)
    }

    /**
     * A real audio output line. Opt-in exactly like hardware decode: a
     * headless runner has no device, and a suite that quietly skips its
     * hardware reads exactly like one that passed. Listed in
     * SKINEMA_REQUIRE_CAPS its absence is a loud failure instead.
     */
    fun assumeAudioDevice() {
        if (requires("audio")) {
            check(capLoads("audio")) { "SKINEMA_REQUIRE_CAPS lists 'audio' but no output line would open here" }
            return
        }
        assumeTrue(
            System.getenv("SKINEMA_TEST_AUDIO") == "1" && capLoads("audio"),
            "audio device acceptance is opt-in (SKINEMA_TEST_AUDIO=1) and needs a real output line",
        )
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
     * Animated-webp decoding is an OPTIONAL capability: the whitelist can be
     * built without it, and a bundle then plays still WebP only. Listed as
     * 'webp' in SKINEMA_REQUIRE_CAPS its absence is a loud failure; otherwise
     * a skip. The webp suite reaches this only past [assumeEncoder], which can
     * skip first on a CLI without the libwebp encoder -- so the capability
     * itself is held by [CapabilitiesTest], not only here.
     */
    fun assumeWebpDecoding() {
        if (requires("webp")) {
            check(capLoads("webp")) { "SKINEMA_REQUIRE_CAPS lists 'webp' but the webp_anim decoder is absent" }
            return
        }
        assumeTrue(capLoads("webp"), "no webp_anim decoder -- optional capability, skipping")
    }

    /**
     * DVB subtitles are bitmap subtitles like PGS, and gate on the bundle's
     * own decoder rather than on libass.
     *
     * Under a capability of their own, not under 'subs'. Every row of the
     * matrix holds subtitles mandatory, so a decoder newer than the bundle CI
     * downloads would have turned every row red rather than skipping two
     * tests -- which is what a required capability is for and exactly not what
     * this is. It joins 'subs' in the required list once a bundle carrying it
     * has shipped.
     */
    fun assumeDvbSubtitles() {
        if (requires("dvbsub")) {
            check(capLoads("dvbsub")) { "SKINEMA_REQUIRE_CAPS lists 'dvbsub' but the bundle has no DVB decoder" }
            return
        }
        assumeTrue(capLoads("dvbsub"), "DVB subtitle decode absent in the bundle -- skipping")
    }

    /**
     * Closed captions, on the same terms as DVB: optional until a bundle
     * carrying the decoder has shipped, then named in the required list.
     *
     * Note what this does NOT gate. The fixture is built here rather than by
     * the ffmpeg CLI -- there is no CEA-608 encoder anywhere in FFmpeg -- so
     * generating one needs nothing of the bundle. What needs the bundle is
     * reading it back, which is this decoder.
     */
    fun assumeClosedCaptions() {
        if (requires("cea608")) {
            check(capLoads("cea608")) { "SKINEMA_REQUIRE_CAPS lists 'cea608' but the bundle has no cc_dec" }
            return
        }
        assumeTrue(capLoads("cea608"), "closed-caption decode absent in the bundle -- skipping")
    }

    /**
     * The broad legacy/extended decode set (the formats feature) is OPTIONAL
     * -- the core tier ships without it. Absence is legal unless
     * SKINEMA_REQUIRE_CAPS lists 'formats'; a core bundle skips these tests.
     */
    fun assumeFormats() {
        if (requires("formats")) {
            check(capLoads("formats")) { "SKINEMA_REQUIRE_CAPS lists 'formats' but the extended decoders did not load" }
            return
        }
        assumeTrue(capLoads("formats"), "extended formats absent in the bundle -- skipping")
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
     * Whether the fixture CLI has [encoder], for a test that sweeps several
     * containers and wants to include one more when it can rather than skip
     * the lot when it cannot.
     */
    fun hasCliEncoder(encoder: String): Boolean = encoder in encoders

    /**
     * Skips when the fixture CLI lacks [encoder]. Deliberately NOT a
     * SKINEMA_REQUIRE_CAPS capability: what the runner's CLI can encode is
     * the environment's business, not part of skinema's decode contract.
     *
     * Where the environment's business became skinema's, the answer is a
     * second route rather than a skip -- see [animatedWebp] and [av1].
     */
    fun assumeEncoder(encoder: String) {
        assumeTrue(encoder in encoders, "CLI lacks encoder $encoder -- fixture impossible, skipping")
    }

    // -- fixtures the CLI cannot always build --------------------------------

    /**
     * Take the second route even where the first one exists. The platform
     * that needs it is not the one this usually runs on, so without a way to
     * ask for it the route would be exercised nowhere but there.
     */
    private val preferFallback = System.getenv("SKINEMA_FIXTURE_FALLBACK") == "1"

    private fun toolOnPath(tool: String): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).any { dir ->
            dir.isNotEmpty() && (File(dir, tool).canExecute() || File(dir, "$tool.exe").canExecute())
        }

    private fun runTool(vararg cmd: String) {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val log = proc.inputStream.readAllBytes().decodeToString()
        check(proc.waitFor() == 0) { "${cmd.first()} failed: $log" }
    }

    private fun cliCan(encoder: String): Boolean = !preferFallback && hasCliEncoder(encoder)

    /**
     * Whether the WebP fixtures can be built here, by either route. Both
     * tools, because the suite needs a still one as well as animations and
     * the second route builds those with different halves of libwebp's own
     * command line -- they ship together, so asking for both costs nothing
     * and half a route is worse than none.
     */
    fun canBuildAnimatedWebp(): Boolean =
        cliCan("libwebp") || (toolOnPath("img2webp") && toolOnPath("cwebp"))

    /** Whether an AV1 fixture can be built here, by either route. */
    fun canBuildAv1(): Boolean =
        cliCan("libaom-av1") || cliCan("libsvtav1") || toolOnPath("SvtAv1EncApp")

    fun assumeAnimatedWebpFixture() {
        assumeTrue(canBuildAnimatedWebp(), "neither the CLI's libwebp nor img2webp here -- skipping")
    }

    fun assumeAv1Fixture() {
        assumeTrue(canBuildAv1(), "neither an AV1 encoder in the CLI nor SvtAv1EncApp here -- skipping")
    }

    /**
     * An animated WebP: [seconds] of a test pattern at [size] and [rate].
     *
     * Two routes, because the first one is not everywhere. Homebrew's ffmpeg
     * carries neither libwebp nor libaom, so on macOS the encoder route does
     * not exist -- and the animated-WebP suite skipped there for the life of
     * the matrix, leaving a decoder that ships in every tier never once
     * exercised on the platform. The second route asks the CLI only for PNG
     * frames, which any build can write, and hands them to libwebp's own
     * img2webp.
     */
    fun animatedWebp(
        target: Path,
        seconds: Int = 1,
        size: String = "64x64",
        rate: Int = 10,
        /**
         * Half-transparent red rather than the test pattern, encoded
         * losslessly so the alpha byte survives to be read back. Both routes
         * take it: PNG carries alpha, and img2webp is told to keep it.
         */
        alpha: Boolean = false,
    ): Path {
        val lavfi = if (alpha) "color=c=red@0.5:size=$size:rate=$rate,format=rgba" else "testsrc2=size=$size:rate=$rate"
        if (cliCan("libwebp")) {
            val pixels = if (alpha) listOf("-pix_fmt", "yuva420p") else emptyList()
            return generate(
                target,
                "-f", "lavfi", "-i", lavfi, "-t", "$seconds",
                "-c:v", "libwebp", "-lossless", if (alpha) "1" else "0", "-loop", "0", *pixels.toTypedArray(),
            )
        }
        withPngFrames(target, lavfi, seconds) { pngs ->
            // -loop and -o are file options; -d and -lossless apply to the
            // frames that follow them, so they come before the list.
            val quality = if (alpha) listOf("-lossless") else emptyList()
            runTool(
                "img2webp", "-loop", "0", "-d", "${1_000 / rate}", *quality.toTypedArray(),
                *pngs.toTypedArray(), "-o", target.toString(),
            )
        }
        return target
    }

    /**
     * A still WebP -- one frame, no animation container. The second route is
     * libwebp's other binary; img2webp would wrap even a single frame as an
     * animation, which is the opposite of what this fixture is for.
     */
    fun stillWebp(target: Path, size: String = "64x64"): Path {
        if (cliCan("libwebp")) {
            return generate(
                target,
                "-f", "lavfi", "-i", "testsrc2=size=$size:rate=10", "-frames:v", "1", "-c:v", "libwebp",
            )
        }
        withPngFrames(target, "testsrc2=size=$size:rate=10", seconds = null) { pngs ->
            runTool("cwebp", "-quiet", pngs.first(), "-o", target.toString())
        }
        return target
    }

    /**
     * Writes [lavfi] out as PNG frames -- which any ffmpeg build can do,
     * encoder or no encoder -- and hands them to [build] in order.
     */
    private fun withPngFrames(target: Path, lavfi: String, seconds: Int?, build: (List<String>) -> Unit) {
        val frames = Files.createTempDirectory(target.parent, "webp-frames")
        try {
            val length = if (seconds == null) listOf("-frames:v", "1") else listOf("-t", "$seconds")
            runTool(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", lavfi, *length.toTypedArray(),
                frames.resolve("f-%03d.png").toString(),
            )
            val pngs = Files.list(frames).use { stream -> stream.sorted().map(Path::toString).toList() }
            check(pngs.isNotEmpty()) { "the CLI wrote no PNG frames for ${target.fileName}" }
            build(pngs)
            check(Files.size(target) > 0) { "the fixture builder produced an empty ${target.fileName}" }
        } finally {
            frames.toFile().deleteRecursively()
        }
    }

    /**
     * An AV1 clip in mp4: [seconds] of a test pattern at [size] and [rate].
     *
     * Same two routes and the same reason. The second one asks the CLI for
     * y4m, which needs no video encoder at all, hands it to SVT-AV1's own
     * binary, and remuxes the result -- copying a stream, which needs no
     * encoder either.
     */
    fun av1(target: Path, seconds: Int = 1, size: String = "64x64", rate: Int = 10): Path {
        if (cliCan("libaom-av1")) {
            return generate(
                target,
                "-f", "lavfi", "-i", "testsrc2=size=$size:rate=$rate", "-t", "$seconds",
                "-pix_fmt", "yuv420p", "-c:v", "libaom-av1", "-cpu-used", "8", "-crf", "40",
            )
        }
        if (cliCan("libsvtav1")) {
            return generate(
                target,
                "-f", "lavfi", "-i", "testsrc2=size=$size:rate=$rate", "-t", "$seconds",
                "-pix_fmt", "yuv420p", "-c:v", "libsvtav1", "-preset", "12",
            )
        }
        val raw = target.resolveSibling("${target.fileName}.y4m")
        val ivf = target.resolveSibling("${target.fileName}.ivf")
        try {
            generate(
                raw,
                "-f", "lavfi", "-i", "testsrc2=size=$size:rate=$rate", "-t", "$seconds", "-pix_fmt", "yuv420p",
            )
            runTool("SvtAv1EncApp", "-i", raw.toString(), "--preset", "12", "-b", ivf.toString())
            return generate(target, "-i", ivf.toString(), "-c", "copy")
        } finally {
            Files.deleteIfExists(raw)
            Files.deleteIfExists(ivf)
        }
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

    /**
     * The same question asked of one encoder among several, for a test that
     * sweeps whatever the runner carries rather than skipping wholesale.
     */
    fun libraryHasEncoder(name: String): Boolean = libavHasEncoder(name)

    /** Whether the loaded libav exposes decoder [name] (avcodec_find_decoder_by_name). */
    private fun libavHasDecoder(name: String): Boolean = libavResolves(name, Libav::avcodecFindDecoderByName)

    /**
     * The same question for a sweep over many codecs. What the fixture CLI
     * can ENCODE and what the loaded library can DECODE are different sets --
     * the shipped bundles carry a deliberately narrow decoder list, and a
     * sweep gated on the CLI alone asserts against codecs the bundle was
     * built without.
     */
    fun libraryHasDecoder(name: String): Boolean = libavHasDecoder(name)

    private fun libavResolves(name: String, find: (MemorySegment) -> MemorySegment): Boolean = runCatching {
        Arena.ofConfined().use { a -> find(a.allocateFrom(name)) != MemorySegment.NULL }
    }.getOrDefault(false)

    /**
     * A font file from this machine, or null when it ships none.
     *
     * Platform directories rather than `fc-match`, and all three platforms
     * rather than Linux alone. Attachments are how anime releases carry their
     * typesetting faces, so the extraction that hands them to libass is worth
     * proving everywhere -- and gated on a Linux-only path list it was proven
     * on Linux only: macOS and Windows skipped those tests forever, silently,
     * with the skip ceiling absorbing both.
     *
     * Collections (.ttc) are left out deliberately. The fixtures attach the
     * file under a plain ttf mimetype, and a collection carried under that
     * label is a second thing to get right for nothing this suite asks.
     */
    /** Faces the three platforms ship, tried before whatever sorts first. */
    private val PREFERRED_FONTS =
        listOf("DejaVuSans", "LiberationSans", "NotoSans", "Arial", "Helvetica", "segoeui", "Verdana")

    fun hostFont(): Path? {
        val roots = when (Os.current()) {
            Os.MAC -> listOf("/System/Library/Fonts/Supplemental", "/System/Library/Fonts", "/Library/Fonts")
            Os.WINDOWS -> listOf(System.getenv("WINDIR")?.plus("\\Fonts") ?: "C:\\Windows\\Fonts")
            Os.LINUX -> listOf("/usr/share/fonts", "/usr/local/share/fonts")
        }
        for (root in roots) {
            val dir = Path.of(root)
            if (!Files.isDirectory(dir)) continue
            val found = runCatching {
                Files.walk(dir).use { stream ->
                    val fonts = stream.filter { Files.isRegularFile(it) && Files.isReadable(it) }
                        .filter { f ->
                            val n = f.fileName.toString().lowercase()
                            n.endsWith(".ttf") || n.endsWith(".otf")
                        }
                        // Sorted, so a runner that fails names a font the next
                        // run picks again rather than a different one.
                        .sorted()
                        .toList()
                    // A face the platform is known to ship first, any face
                    // second. Whatever sorts first in a distribution's font
                    // directory can be a bitmap or a symbol set, and a fixture
                    // that trips over one of those fails for a reason that has
                    // nothing to do with what it tests.
                    fonts.firstOrNull { f ->
                        PREFERRED_FONTS.any { f.fileName.toString().startsWith(it, ignoreCase = true) }
                    } ?: fonts.firstOrNull()
                }
            }.getOrNull()
            if (found != null) return found
        }
        return null
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
