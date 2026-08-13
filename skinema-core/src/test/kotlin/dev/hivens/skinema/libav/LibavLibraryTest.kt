package dev.hivens.skinema.libav

import kotlin.test.Test
import kotlin.test.assertEquals

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
        // library name to a .dll.
        val probe = { name: String ->
            val previous = System.getProperty("os.name")
            System.setProperty("os.name", name)
            try { Os.current() } finally { System.setProperty("os.name", previous) }
        }
        assertEquals(Os.MAC, probe("Mac OS X"))
        assertEquals(Os.MAC, probe("Darwin"))
        assertEquals(Os.WINDOWS, probe("Windows 11"))
        assertEquals(Os.LINUX, probe("Linux"))
    }

    @Test
    fun `declaration order loads dependencies before their dependents`() {
        val order = LibavLibrary.entries
        // avutil underpins everything; avfilter may link any of the rest.
        assertEquals(LibavLibrary.AVUTIL, order.first())
        assertEquals(LibavLibrary.AVFILTER, order.last())
    }
}
