package dev.hivens.skinema.libav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibavLibraryTest {

    @Test
    fun `file names follow each platform's native naming convention`() {
        // Derived from the pin, not spelled out: the convention is what this
        // asserts, and a bump should not have to edit it. The majors
        // themselves are held below, where changing them reads as a decision.
        val major = LibavLibrary.AVFORMAT.sonameMajor
        assertEquals("libavformat.so.$major", LibavLibrary.AVFORMAT.fileName(Os.LINUX))
        assertEquals("libavformat.$major.dylib", LibavLibrary.AVFORMAT.fileName(Os.MAC))
        assertEquals("avformat-$major.dll", LibavLibrary.AVFORMAT.fileName(Os.WINDOWS))
    }

    /**
     * The pinned set, spelled out. [LibavAbi]'s offsets are only valid for
     * these majors, so moving one is a bump -- re-run tools/layout-oracle.c
     * against the new line and transcribe before touching this.
     */
    @Test
    fun `the pinned soname majors are the n9 line`() {
        assertEquals(
            mapOf(
                LibavLibrary.AVUTIL to 61,
                LibavLibrary.SWRESAMPLE to 7,
                LibavLibrary.SWSCALE to 10,
                LibavLibrary.AVCODEC to 63,
                LibavLibrary.AVFORMAT to 63,
                LibavLibrary.AVFILTER to 12,
            ),
            LibavLibrary.entries.associateWith { it.sonameMajor },
        )
    }

    @Test
    fun `the OS probe reads mac before windows`() {
        // "darwin" contains "win"; a JVM answering it must not resolve every
        // library name to a .dll. Against the pure mapping, never a spoofed
        // os.name -- LibavAbi.AVERROR_EAGAIN latches off the real one once
        // per process, and getting that wrong spins the receive loop forever.
        assertEquals(Os.MAC, Os.of("Mac OS X"))
        assertEquals(Os.MAC, Os.of("Darwin"))
        assertEquals(Os.WINDOWS, Os.of("Windows 11"))
        assertEquals(Os.LINUX, Os.of("Linux"))
    }

    @Test
    fun `declaration order loads dependencies before their dependents`() {
        val order = LibavLibrary.entries
        // avutil underpins everything; avfilter may link any of the rest.
        assertEquals(LibavLibrary.AVUTIL, order.first())
        assertEquals(LibavLibrary.AVFILTER, order.last())
    }

    /**
     * /proc/self/maps excerpts captured from running processes -- the glibc
     * one from this repo's CI image, the rest from Alpine containers.
     *
     * Each case defeats a plausible shortcut: matching the last
     * whitespace-separated token misses a path the kernel padded or a file
     * that has been unlinked, and matching the musl marker anywhere in the
     * text calls gcompat's glibc process musl.
     */
    @Test
    fun `musl is detected from the process own mappings, not from what is installed`() {
        val glibc = """
            7f3c1a000000-7f3c1a028000 r--p 00000000 08:02 1234  /usr/lib/x86_64-linux-gnu/libc.so.6
            7f3c1a3d0000-7f3c1a3d2000 r--p 00000000 08:02 5678  /usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2
        """.trimIndent()
        val musl = """
            7f9b4c000000-7f9b4c022000 r-xp 00000000 08:03 4321  /lib/ld-musl-x86_64.so.1
            7f9b4c400000-7f9b4c401000 rw-p 00000000 00:00 0     [heap]
        """.trimIndent()
        // A glibc binary run through Alpine's gcompat. The shim is itself a
        // musl program, so musl's loader is mapped -- but the process needs
        // glibc objects, and handing it musl ones cannot work.
        val gcompat = """
            7f71a63d7000-7f71a63d9000 r-xp 00000000 00:2f 1799  /usr/bin/some-glibc-tool
            7f71a63c5000-7f71a63c7000 r-xp 00000000 00:2f 1806  /lib/libgcompat.so.0
            7f71a63e9000-7f71a6420000 r-xp 00000000 00:2f 1793  /lib/ld-musl-x86_64.so.1
        """.trimIndent()
        // `apk upgrade musl` installs to a temp name and renames, so a JVM
        // that was already running sees every musl mapping unlinked.
        val deleted = "7fb9f269d000-7fb9f26cb000 r-xp 00000000 08:03 123  /lib/ld-musl-x86_64.so.1 (deleted)"
        // The kernel does not escape spaces in the path (unlike /proc/mounts).
        val spaced = "7f00aa000000-7f00aa001000 r--p 00000000 08:03 9  /opt/my libs/libc.musl-x86_64.so.1"

        assertEquals(false, isMuslLinked(glibc))
        assertEquals(true, isMuslLinked(musl))
        assertEquals(false, isMuslLinked(gcompat), "a glibc process under gcompat needs glibc objects")
        assertEquals(true, isMuslLinked(deleted), "an unlinked musl loader is still a musl process")
        assertEquals(true, isMuslLinked(spaced), "a padded path is not the last token on the line")
        assertEquals(false, isMuslLinked(""), "no mappings is not musl")
    }

    @Test
    fun `the platform tag is the classifier the natives jars publish`() {
        // Read the tags the natives module actually publishes rather than
        // restating them: a hand-copied list agrees with itself while the two
        // sides drift, and the drift is a jar coordinate that never resolves.
        var dir = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (!java.io.File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("no settings.gradle.kts above ${System.getProperty("user.dir")}")
        }
        val build = java.io.File(dir, "skinema-natives/build.gradle.kts").readText()
        val platforms = Regex("\"((?:linux|macos|windows)(?:-musl)?-(?:x64|arm64))\"")
            .findAll(build).map { it.groupValues[1] }.toSet()

        assertEquals(8, platforms.size, "expected 8 published platform tags, got $platforms")
        assertTrue(nativesPlatform() in platforms, "unknown platform tag ${nativesPlatform()} (published: $platforms)")
    }
}
