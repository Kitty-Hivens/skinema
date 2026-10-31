package dev.hivens.skinema.libav

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The get_format negotiation on its own, with no device and no file, so the
 * one piece of hardware decode that is pure logic is checked where the rest
 * of it cannot be: a runner without a GPU (#29).
 *
 * The upcall is called the way avcodec calls it -- through the function
 * pointer, with a context and a NONE-terminated format list -- against a
 * context this test allocates itself. Only the opaque slot is read, so a
 * zeroed block of the right size is a faithful stand-in for the real struct.
 */
class HwFormatNegotiationTest {

    // Shared, not confined: the thread-independence case reads the context
    // from another thread, which is what avcodec does, and a real
    // avcodec_alloc_context3 block belongs to no thread either.
    private val arena = Arena.ofShared()

    @AfterTest
    fun cleanup() = arena.close()

    // A bundle that cannot load is a skip like everywhere else, so the handle
    // is built on first use rather than at construction -- touching Libav is
    // what loads the libraries.
    @BeforeTest
    fun requireLibav() = Fixtures.assumeDecodeEnvironment()

    private val getFormat by lazy {
        Linker.nativeLinker().downcallHandle(
            Libav.getFormatUpcall(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
        )
    }

    /** A zeroed AVCodecContext whose opaque slot carries [target], or nothing for software. */
    private fun context(target: Int?): MemorySegment {
        val ctx = arena.allocate(LibavAbi.CodecContext.SIZEOF)
        if (target != null) {
            val slot = arena.allocate(JAVA_INT)
            slot.set(JAVA_INT, 0, target)
            ctx.set(ADDRESS, LibavAbi.CodecContext.OPAQUE, slot)
        }
        return ctx
    }

    /** avcodec's candidate list: the formats it can emit, terminated by NONE. */
    private fun formats(vararg pixFmts: Int): MemorySegment {
        val list = arena.allocate(JAVA_INT.byteSize() * (pixFmts.size + 1))
        pixFmts.forEachIndexed { i, fmt -> list.setAtIndex(JAVA_INT, i.toLong(), fmt) }
        list.setAtIndex(JAVA_INT, pixFmts.size.toLong(), LibavAbi.AV_PIX_FMT_NONE)
        return list
    }

    private fun negotiate(ctx: MemorySegment, list: MemorySegment): Int =
        getFormat.invoke(ctx, list) as Int

    @Test
    fun `the surface the context asks for wins`() {
        assertEquals(
            LibavAbi.AV_PIX_FMT_VAAPI,
            negotiate(
                context(LibavAbi.AV_PIX_FMT_VAAPI),
                formats(LibavAbi.AV_PIX_FMT_VAAPI, LibavAbi.AV_PIX_FMT_YUV420P),
            ),
        )
    }

    @Test
    fun `a context asking for nothing takes the software entry`() {
        assertEquals(
            LibavAbi.AV_PIX_FMT_YUV420P,
            negotiate(context(null), formats(LibavAbi.AV_PIX_FMT_VAAPI, LibavAbi.AV_PIX_FMT_YUV420P)),
        )
    }

    @Test
    fun `a hardware format from another backend is not taken`() {
        // Two backends on one machine (QSV and VAAPI on an Intel box): taking
        // whichever hardware format comes first yields a surface the opened
        // device cannot fill, and the decode fails (#2).
        assertEquals(
            LibavAbi.AV_PIX_FMT_YUV420P,
            negotiate(
                context(LibavAbi.AV_PIX_FMT_VAAPI),
                formats(LibavAbi.AV_PIX_FMT_QSV, LibavAbi.AV_PIX_FMT_YUV420P),
            ),
        )
    }

    @Test
    fun `each context keeps its own surface`() {
        val hw = context(LibavAbi.AV_PIX_FMT_VAAPI)
        val software = context(null)
        val list = formats(LibavAbi.AV_PIX_FMT_VAAPI, LibavAbi.AV_PIX_FMT_YUV420P)
        // Interleaved, because the failure this guards was a second decoder
        // opened between the first one's open and its first decode.
        assertEquals(LibavAbi.AV_PIX_FMT_YUV420P, negotiate(software, list))
        assertEquals(LibavAbi.AV_PIX_FMT_VAAPI, negotiate(hw, list))
        assertEquals(LibavAbi.AV_PIX_FMT_YUV420P, negotiate(software, list))
    }

    @Test
    fun `negotiation does not depend on the calling thread`() {
        // avcodec calls get_format from whichever thread is decoding, and a
        // frame-threaded decoder decodes on workers it creates itself -- never
        // on the thread that opened the file. A target the opener leaves on
        // its own thread is therefore absent exactly when it is read, and the
        // negotiation falls through to software with a device standing open.
        val ctx = context(LibavAbi.AV_PIX_FMT_VAAPI)
        val list = formats(LibavAbi.AV_PIX_FMT_VAAPI, LibavAbi.AV_PIX_FMT_YUV420P)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val chosen = worker.submit<Int> { negotiate(ctx, list) }.get(10, TimeUnit.SECONDS)
            assertEquals(LibavAbi.AV_PIX_FMT_VAAPI, chosen, "a foreign thread must negotiate the same surface")
        } finally {
            worker.shutdownNow()
        }
    }
}
