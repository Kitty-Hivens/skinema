package dev.hivens.skinema.webp

import dev.hivens.skinema.libav.Libav
import dev.hivens.skinema.libav.Os
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle

/**
 * Hand-written downcalls to libwebpdemux's WebPAnimDecoder -- the decoder
 * for the one format FFmpeg cannot handle (animated WebP; upstream only
 * decodes stills). Same discipline as [Libav]: exact sonames, the
 * directory override, an offline oracle for the ABI.
 *
 * Strictly optional: when the libraries are absent [available] is false
 * and RIFF/WEBP files fall back to the libav path (stills decode there;
 * animations fail closed into the consumer's fallback, the pre-addon
 * behavior).
 */
object Webp {

    // libtool naming, not ffmpeg's: Windows DLLs keep the lib prefix.
    private fun fileName(base: String, major: Int): String = when (Os.current()) {
        Os.LINUX -> "lib$base.so.$major"
        Os.MAC -> "lib$base.$major.dylib"
        Os.WINDOWS -> "lib$base-$major.dll"
    }

    private class Bindings {
        private val linker = Linker.nativeLinker()

        // libwebp first: libwebpdemux needs its symbols, and loading it
        // explicitly lets the bundle's copy win over any system one. Unlike
        // the pinned libav set, this capability is optional -- when the
        // bundle does not carry it yet, the system copy is welcome.
        private fun lookup(base: String, major: Int): SymbolLookup {
            val name = fileName(base, major)
            val overridden = Libav.resolveLibraryPath(name)
            return runCatching { SymbolLookup.libraryLookup(overridden, Arena.global()) }
                .recoverCatching { failure ->
                    if (overridden == name) throw failure
                    SymbolLookup.libraryLookup(name, Arena.global())
                }
                .getOrThrow()
        }

        init {
            // libwebp links libsharpyuv; preload it so a bundled copy
            // resolves the dependency by soname. Absence is fine -- older
            // system libwebp builds carry no sharpyuv at all.
            runCatching { lookup("sharpyuv", 0) }
        }

        private val webp = lookup("webp", 7)
        private val demux = lookup("webpdemux", 2)

        private fun fn(name: String, descriptor: FunctionDescriptor): MethodHandle {
            val symbol = demux.find(name).orElseThrow { UnsatisfiedLinkError("libwebpdemux exports no '$name'") }
            return linker.downcallHandle(symbol, descriptor)
        }

        val optionsInit = fn("WebPAnimDecoderOptionsInitInternal", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
        val new = fn("WebPAnimDecoderNewInternal", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT))
        val getInfo = fn("WebPAnimDecoderGetInfo", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        val getNext = fn("WebPAnimDecoderGetNext", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
        val hasMoreFrames = fn("WebPAnimDecoderHasMoreFrames", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val reset = fn("WebPAnimDecoderReset", FunctionDescriptor.ofVoid(ADDRESS))
        val delete = fn("WebPAnimDecoderDelete", FunctionDescriptor.ofVoid(ADDRESS))

        init {
            // Touch libwebp so an absent library fails here, inside the
            // availability probe, not on the first frame.
            webp.find("WebPFree").orElseThrow { UnsatisfiedLinkError("libwebp exports no WebPFree") }
        }
    }

    private val bindings: Bindings? = runCatching { Bindings() }.getOrNull()

    /** True when libwebp + libwebpdemux loaded; false degrades to the libav path. */
    val available: Boolean get() = bindings != null

    private fun b(): Bindings = checkNotNull(bindings) { "libwebpdemux is not available" }

    fun optionsInit(options: MemorySegment): Int =
        b().optionsInit.invoke(options, WebpAbi.DEMUX_ABI_VERSION) as Int

    fun decoderNew(data: MemorySegment, options: MemorySegment): MemorySegment =
        b().new.invoke(data, options, WebpAbi.DEMUX_ABI_VERSION) as MemorySegment

    fun getInfo(decoder: MemorySegment, info: MemorySegment): Int = b().getInfo.invoke(decoder, info) as Int

    fun getNext(decoder: MemorySegment, bufOut: MemorySegment, timestampOut: MemorySegment): Int =
        b().getNext.invoke(decoder, bufOut, timestampOut) as Int

    fun hasMoreFrames(decoder: MemorySegment): Boolean = (b().hasMoreFrames.invoke(decoder) as Int) != 0

    fun reset(decoder: MemorySegment) {
        b().reset.invoke(decoder)
    }

    fun delete(decoder: MemorySegment) {
        b().delete.invoke(decoder)
    }
}
