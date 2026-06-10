package dev.hivens.skinema.libav

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeBundleTest {

    private val dir: Path = Files.createTempDirectory("skinema-bundle-test")
    private val cache: Path = dir.resolve("cache")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    /** Lays out a fake bundle classpath root and returns a loader over it. */
    private fun bundleLoader(platform: String, files: Map<String, String>, fingerprint: String = "fp-1"): ClassLoader {
        val root = dir.resolve("bundle-root/dev/hivens/skinema/natives/$platform").createDirectories()
        root.resolve("index.txt").writeText(
            (listOf(fingerprint) + files.keys).joinToString("\n"),
        )
        files.forEach { (name, content) ->
            root.resolve(name).also { it.parent.createDirectories() }.writeText(content)
        }
        return URLClassLoader(arrayOf(dir.resolve("bundle-root").toUri().toURL()), null)
    }

    @Test
    fun `deploys the indexed files under the fingerprint directory`() {
        val loader = bundleLoader("linux-x64", mapOf("libavutil.so.60" to "fake-avutil", "licenses/LICENSE.md" to "lgpl"))
        val deployed = NativeBundle.deploy(loader, cache, "linux-x64")!!
        assertEquals(cache.resolve("fp-1"), deployed)
        assertEquals("fake-avutil", deployed.resolve("libavutil.so.60").readText())
        assertEquals("lgpl", deployed.resolve("licenses/LICENSE.md").readText())
    }

    @Test
    fun `no bundle on the classpath means null, not an error`() {
        assertNull(NativeBundle.deploy(URLClassLoader(emptyArray(), null), cache, "linux-x64"))
    }

    @Test
    fun `a second deploy reuses the existing fingerprint directory`() {
        val loader = bundleLoader("linux-x64", mapOf("libavutil.so.60" to "v1"))
        val first = NativeBundle.deploy(loader, cache, "linux-x64")!!
        // Corrupt the cached copy; reuse means deploy must NOT re-extract.
        first.resolve("libavutil.so.60").writeText("touched")
        val second = NativeBundle.deploy(loader, cache, "linux-x64")!!
        assertEquals(first, second)
        assertEquals("touched", second.resolve("libavutil.so.60").readText())
    }

    @Test
    fun `a changed fingerprint deploys fresh alongside the old one`() {
        val v1 = bundleLoader("linux-x64", mapOf("libavutil.so.60" to "v1"), fingerprint = "fp-1")
        NativeBundle.deploy(v1, cache, "linux-x64")
        dir.resolve("bundle-root").toFile().deleteRecursively()
        val v2 = bundleLoader("linux-x64", mapOf("libavutil.so.60" to "v2"), fingerprint = "fp-2")
        val deployed = NativeBundle.deploy(v2, cache, "linux-x64")!!
        assertEquals("v2", deployed.resolve("libavutil.so.60").readText())
        assertTrue(Files.isDirectory(cache.resolve("fp-1")), "the old bundle stays for processes still using it")
    }

    @Test
    fun `index entries cannot escape the bundle directory`() {
        val loader = bundleLoader("linux-x64", mapOf("ok" to "x"))
        // Hand-craft a malicious index next to the legit files.
        dir.resolve("bundle-root/dev/hivens/skinema/natives/linux-x64/index.txt")
            .writeText("fp-evil\n../../escape")
        assertFailsWith<IllegalArgumentException> { NativeBundle.deploy(loader, cache, "linux-x64") }
    }

    @Test
    fun `an index listing a missing file fails the deploy and leaves no cache entry`() {
        val loader = bundleLoader("linux-x64", mapOf("present" to "x"))
        dir.resolve("bundle-root/dev/hivens/skinema/natives/linux-x64/index.txt")
            .writeText("fp-3\npresent\nmissing")
        assertFailsWith<IllegalStateException> { NativeBundle.deploy(loader, cache, "linux-x64") }
        assertTrue(!Files.isDirectory(cache.resolve("fp-3")), "a failed deploy must not look deployed")
    }
}
