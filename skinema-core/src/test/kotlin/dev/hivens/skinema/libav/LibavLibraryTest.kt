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
     * Real /proc/self/maps excerpts. The glibc one is from this repo's own
     * CI shape, the musl one from an Alpine JVM; the mixed case is a glibc
     * process on a host that also has musl installed, which is exactly where
     * a filesystem probe would answer wrongly.
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
        // glibc process, musl merely present on disk (gcompat and friends).
        val mixed = glibc + "\n7f00aa000000-7f00aa001000 r--p 00000000 08:03 9  /usr/lib/libc.musl-x86_64.so.1.bak"

        assertEquals(false, isMuslLinked(glibc))
        assertEquals(true, isMuslLinked(musl))
        assertEquals(true, isMuslLinked(mixed), "a mapped musl libc counts wherever it came from")
        assertEquals(false, isMuslLinked(""), "no mappings is not musl")
    }

    @Test
    fun `the platform tag is the classifier the natives jars publish`() {
        // Whatever this host is, the tag must be one the build actually ships.
        val shipped = setOf(
            "linux-x64", "linux-arm64", "linux-musl-x64", "linux-musl-arm64",
            "windows-x64", "windows-arm64", "macos-arm64", "macos-x64",
        )
        assertTrue(nativesPlatform() in shipped, "unknown platform tag ${nativesPlatform()}")
    }
}
