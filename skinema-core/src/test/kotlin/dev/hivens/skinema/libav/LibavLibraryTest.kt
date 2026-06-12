package dev.hivens.skinema.libav

import kotlin.test.Test
import kotlin.test.assertEquals

class LibavLibraryTest {

    @Test
    fun `file names follow each platform's native naming convention`() {
        assertEquals("libavformat.so.62", LibavLibrary.AVFORMAT.fileName(Os.LINUX))
        assertEquals("libavformat.62.dylib", LibavLibrary.AVFORMAT.fileName(Os.MAC))
        assertEquals("avformat-62.dll", LibavLibrary.AVFORMAT.fileName(Os.WINDOWS))
    }

    @Test
    fun `declaration order loads dependencies before their dependents`() {
        val order = LibavLibrary.entries
        // avutil underpins everything; avfilter may link any of the rest.
        assertEquals(LibavLibrary.AVUTIL, order.first())
        assertEquals(LibavLibrary.AVFILTER, order.last())
    }
}
