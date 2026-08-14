package dev.hivens.skinema.libav

import dev.hivens.skinema.Debug

/** Host platform, as far as native library naming is concerned. */
enum class Os {
    LINUX, MAC, WINDOWS;

    companion object {
        fun current(): Os = of(System.getProperty("os.name", ""))

        /**
         * The pure half, so the mapping is testable without writing to a
         * global the whole JVM reads -- [LibavAbi.AVERROR_EAGAIN] latches
         * off [current] once per process, and a test that spoofed os.name
         * could latch it wrong for every later test in the run.
         *
         * Mac is tested first because "darwin" contains "win". OpenJDK
         * reports "Mac OS X", so the old order held -- but a JVM answering
         * "Darwin" would be read as Windows, resolving every library name
         * to a .dll.
         */
        internal fun of(osName: String): Os {
            val name = osName.lowercase()
            return when {
                name.contains("mac") || name.contains("darwin") -> MAC
                name.contains("win") -> WINDOWS
                else -> LINUX
            }
        }
    }
}

/**
 * "<os>-<arch>" tag the natives bundles use (linux-x64, macos-arm64, ...).
 *
 * Linux splits further by C library: a glibc-built shared object cannot load
 * into a musl process at all -- it wants an interpreter and a `libc.so.6`
 * that do not exist there -- so Alpine and Void-musl take their own
 * `linux-musl-*` bundles rather than a glibc one that resolves and then
 * fails (#33).
 */
fun nativesPlatform(): String {
    val os = when (Os.current()) {
        Os.LINUX -> if (isMuslProcess()) "linux-musl" else "linux"
        Os.MAC -> "macos"
        Os.WINDOWS -> "windows"
    }
    val arch = when (System.getProperty("os.arch", "").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        else -> "x64"
    }
    return "$os-$arch"
}

/**
 * Whether THIS process is linked against musl. Read off the running
 * process's own mappings rather than probed on the filesystem: the question
 * is which C library the JVM that will dlopen these libraries actually uses,
 * and a host can carry both (Alpine with gcompat, a glibc JVM unpacked onto a
 * musl system). A filesystem probe answers "what is installed", which is a
 * different question and gets those hosts wrong.
 *
 * Latin-1, not UTF-8: the markers are ASCII, but a mapped file whose name
 * holds one non-UTF-8 byte would fail the decode of the whole file and
 * silently answer glibc on a musl host. Latin-1 cannot fail on any byte.
 */
private fun isMuslProcess(): Boolean =
    try {
        isMuslLinked(String(java.nio.file.Files.readAllBytes(MAPS), Charsets.ISO_8859_1))
    } catch (e: java.io.IOException) {
        // No /proc (a minimal container, a chroot) leaves the flavour unknown.
        // glibc is the safer guess -- it is the majority of hosts -- but the
        // resulting bundle miss is otherwise indistinguishable from shipping
        // no natives at all, so say so where it can be seen.
        Debug.trace("reading $MAPS to detect the C library", e)
        false
    }

private val MAPS = java.nio.file.Path.of("/proc/self/maps")

/**
 * The pure half of [isMuslProcess], so the parsing is testable without /proc.
 *
 * glibc, once seen, settles it. Under Alpine's gcompat a *glibc* process maps
 * musl's loader too -- gcompat's ld-linux shim is itself a musl program -- so
 * a musl marker alone would hand a glibc process musl objects it cannot load,
 * getting wrong the one host that motivates reading maps in the first place.
 */
internal fun isMuslLinked(procSelfMaps: String): Boolean {
    var musl = false
    for (line in procSelfMaps.lineSequence()) {
        val name = mappedFileName(line) ?: continue
        if (name.startsWith("libgcompat.") ||
            name.startsWith("libc.so.6") ||
            name.startsWith("ld-linux-")
        ) {
            return false
        }
        if (name.startsWith("ld-musl-") || name.startsWith("libc.musl-")) musl = true
    }
    return musl
}

/**
 * The file name of a mapping, or null for an anonymous or pseudo-file one.
 *
 * The path is the sixth field and runs to end of line: the kernel pads to the
 * column with spaces and does not escape spaces inside the path, so the path
 * cannot be taken as the last whitespace-separated token. It also appends
 * " (deleted)" once the file is unlinked -- which is what `apk upgrade musl`
 * does to a running JVM -- and that suffix must not hide the name behind it.
 */
private fun mappedFileName(line: String): String? {
    val path = line.split(' ', limit = 6).getOrNull(5)?.trimStart()?.removeSuffix(" (deleted)")
    if (path.isNullOrEmpty() || path.startsWith('[')) return null
    return path.substringAfterLast('/')
}

/**
 * The libav* shared libraries skinema binds, with the soname major each one
 * carries in the pinned FFmpeg release line (n9.0 -- see ROADMAP.md).
 *
 * Lookups must always request the exact soname, never the bare library name:
 * FFmpeg majors routinely coexist in one system (Arch ships a 4.4 compat
 * package next to 8.x), and the unversioned `libavformat.so` dev-symlink may
 * point at either major -- or be absent entirely, since it belongs to the
 * -dev package on Debian-family systems.
 *
 * Declaration order is a safe load order: every library is preceded by
 * everything it links against (avutil first, avfilter -- which may link
 * any of the others -- last).
 */
enum class LibavLibrary(val baseName: String, val sonameMajor: Int) {
    AVUTIL("avutil", 61),
    SWRESAMPLE("swresample", 7),
    SWSCALE("swscale", 10),
    AVCODEC("avcodec", 63),
    AVFORMAT("avformat", 63),
    AVFILTER("avfilter", 12);

    /** The file name carrying this library's pinned major on [os]. */
    fun fileName(os: Os): String = when (os) {
        Os.LINUX -> "lib$baseName.so.$sonameMajor"
        Os.MAC -> "lib$baseName.$sonameMajor.dylib"
        Os.WINDOWS -> "$baseName-$sonameMajor.dll"
    }
}
