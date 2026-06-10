package dev.hivens.skinema.libav

/** Host platform, as far as native library naming is concerned. */
enum class Os {
    LINUX, MAC, WINDOWS;

    companion object {
        fun current(): Os {
            val name = System.getProperty("os.name", "").lowercase()
            return when {
                name.contains("win") -> WINDOWS
                name.contains("mac") -> MAC
                else -> LINUX
            }
        }
    }
}

/**
 * The libav* shared libraries skinema binds, with the soname major each one
 * carries in the pinned FFmpeg release line (n8.1 -- see ROADMAP.md).
 *
 * Lookups must always request the exact soname, never the bare library name:
 * FFmpeg majors routinely coexist in one system (Arch ships a 4.4 compat
 * package next to 8.x), and the unversioned `libavformat.so` dev-symlink may
 * point at either major -- or be absent entirely, since it belongs to the
 * -dev package on Debian-family systems.
 *
 * Declaration order is a safe load order: every library is preceded by
 * everything it links against (avutil first, avformat last).
 */
enum class LibavLibrary(val baseName: String, val sonameMajor: Int) {
    AVUTIL("avutil", 60),
    SWRESAMPLE("swresample", 6),
    SWSCALE("swscale", 9),
    AVCODEC("avcodec", 62),
    AVFORMAT("avformat", 62);

    /** The file name carrying this library's pinned major on [os]. */
    fun fileName(os: Os): String = when (os) {
        Os.LINUX -> "lib$baseName.so.$sonameMajor"
        Os.MAC -> "lib$baseName.$sonameMajor.dylib"
        Os.WINDOWS -> "$baseName-$sonameMajor.dll"
    }
}
