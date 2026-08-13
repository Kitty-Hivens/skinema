package dev.hivens.skinema.libav

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

/** "<os>-<arch>" tag the natives bundles use (linux-x64, macos-arm64, ...). */
fun nativesPlatform(): String {
    val os = when (Os.current()) {
        Os.LINUX -> "linux"
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
