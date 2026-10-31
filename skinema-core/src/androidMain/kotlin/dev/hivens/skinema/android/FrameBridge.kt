package dev.hivens.skinema.android

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import dev.hivens.skinema.Debug
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Turns the decoder's output into RGBA8888 images this library owns.
 *
 * The player renders into [inputSurface], a SurfaceTexture on a GL thread of
 * our own; each frame is drawn from it into the window surface of an
 * [ImageReader], and the reader's images are what the consumer reads. The
 * decoder's own buffers are never read by the CPU: they belong to the vendor
 * codec, which frees them when it is released, and reading one through a
 * direct consumer surface crashed the process at exactly that moment. The GL
 * draw is also what does the colour conversion, on the GPU, from whatever
 * layout the codec emitted, and what applies the transform the codec attached
 * to the buffer.
 *
 * An image's timestamp is the release time the player gave the frame, which
 * is how the caller maps it back to the frame's media time.
 *
 * A picture the library composed itself (an image file's frame) skips the
 * input surface and goes in through [present], uploaded as a texture and
 * drawn into the same reader, carrying a timestamp the caller picks.
 *
 * [onImage] runs on the GL thread and owns the image it is handed: it gives
 * it back through [release], at once or later, and the reader holds
 * [MAX_IMAGES] of them. Released rather than closed, because closing a reader
 * closes every image it handed out: a reader replaced by a resize is kept
 * until the last of its images comes back, so a frame the consumer still
 * holds stays readable across a change of size.
 *
 * Anything that throws on the GL thread after construction (a reader the
 * graphics memory cannot hold, a surface the driver refuses) goes to
 * [onFailure] rather than up the thread, where it would end the process.
 */
internal class FrameBridge(
    private val onImage: (Image) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {

    private val thread = HandlerThread("skinema-frames").apply { start() }
    private val handler = Handler(thread.looper)

    private lateinit var display: EGLDisplay
    private lateinit var context: EGLContext
    private lateinit var config: EGLConfig
    private lateinit var pbuffer: EGLSurface
    private var texture = 0
    private var program = 0
    private var stillTexture = 0
    private var stillProgram = 0
    private lateinit var surfaceTexture: SurfaceTexture

    /** Where the player renders. Valid from construction until [close]. */
    lateinit var inputSurface: Surface
        private set

    private var reader: ImageReader? = null

    // Which reader each image handed out came from, and the readers a resize
    // replaced while some of their images were still out.
    private val owners = IdentityHashMap<Image, ImageReader>()
    private val retired = HashSet<ImageReader>()
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outWidth = 0
    private var outHeight = 0
    private val transform = FloatArray(16)

    // Whether the texture holds a frame. The first frame is released before
    // the player reports the size the output is built from, so it lands with
    // nowhere to go; it is drawn again once there is, or a paused start would
    // show nothing until something else moved.
    private var latched = false

    // Off while the library presents pictures of its own. A decoder being
    // stopped can still release a frame, and drawn into a reader already
    // sized for the picture it would land as a stray frame of the wrong file.
    private var decoderFrames = true

    @Volatile
    private var closed = false

    // Frames the reader announced while every image was out, still queued in
    // it: taken once the consumer gives one back. Changed on the GL thread,
    // read anywhere as the hint that a give-back has a frame to bring out.
    @Volatile
    private var starved = 0

    // The newest picture handed to present() and not drawn yet. Newer
    // replaces older, so a reader that has stopped taking images holds back
    // one picture, not a queue of them.
    private val pendingStill = AtomicReference<Pair<Bitmap, Long>?>(null)

    init {
        onThread { setUp() }
    }

    /**
     * Sizes the output to [width] x [height], the frame as it is displayed.
     * The reader is replaced only when the size changes, so a repeated call
     * costs nothing. [redraw] draws the decoder's last frame into the new
     * reader, which a caller about to [present] a picture of its own does
     * not want.
     */
    fun resize(width: Int, height: Int, redraw: Boolean = true) {
        if (width <= 0 || height <= 0) return
        guarded {
            if (closed || (width == outWidth && height == outHeight)) return@guarded
            releaseOutput()
            val next = ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, MAX_IMAGES,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN or
                    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
            )
            next.setOnImageAvailableListener({ r -> take(r) }, handler)
            starved = 0
            windowSurface = EGL14.eglCreateWindowSurface(display, config, next.surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(windowSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
            reader = next
            outWidth = width
            outHeight = height
            if (latched && redraw) draw()
        }
    }

    /**
     * Takes the next image out of [r] and hands it on. With every image out,
     * the frame stays queued in the reader and is counted, to be taken when
     * one comes back: the reader announces each frame once.
     */
    private fun take(r: ImageReader) {
        val image = try {
            r.acquireNextImage()
        } catch (e: IllegalStateException) {
            Debug.trace("frame bridge: no image slot free", e)
            if (r === reader) starved++
            null
        } ?: return
        synchronized(owners) { owners[image] = r }
        onImage(image)
    }

    /** Runs [block] on the GL thread, its failure reported rather than thrown there. */
    private fun guarded(block: () -> Unit) {
        handler.post {
            try {
                block()
            } catch (t: Throwable) {
                if (!closed) onFailure(t)
            }
        }
    }

    /**
     * Whether frames arriving on [inputSurface] are drawn. Ordered with
     * [resize] and [present], so a frame that arrives after the call is held
     * to it. Latched either way, or the input stops receiving frames.
     */
    fun acceptDecoderFrames(accept: Boolean) {
        handler.post { decoderFrames = accept }
    }

    fun present(bitmap: Bitmap, timestamp: Long) {
        if (closed) {
            bitmap.recycle()
            return
        }
        pendingStill.getAndSet(bitmap to timestamp)?.first?.recycle()
        guarded { drawStill() }
    }

    private fun drawStill() {
        val (bitmap, timestamp) = pendingStill.getAndSet(null) ?: return
        try {
            if (closed || windowSurface == EGL14.EGL_NO_SURFACE) return
            EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stillTexture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            quad(stillProgram, FLIP_ROWS)
            EGLExt.eglPresentationTimeANDROID(display, windowSurface, timestamp)
            EGL14.eglSwapBuffers(display, windowSurface)
            // What the screen shows now is this picture, so a later resize
            // has no decoder frame to bring back.
            latched = false
        } finally {
            bitmap.recycle()
        }
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no RGBA8888 EGL config" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent failed" }

        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        texture = names[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program = link(VERTEX_SHADER, FRAGMENT_SHADER)
        GLES20.glGenTextures(1, names, 0)
        stillTexture = names[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stillTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        stillProgram = link(VERTEX_SHADER, STILL_FRAGMENT_SHADER)

        surfaceTexture = SurfaceTexture(texture)
        surfaceTexture.setOnFrameAvailableListener({
            try {
                drawFrame()
            } catch (t: Throwable) {
                if (!closed) onFailure(t)
            }
        }, handler)
        inputSurface = Surface(surfaceTexture)
    }

    private fun drawFrame() {
        if (closed) return
        val target = if (windowSurface != EGL14.EGL_NO_SURFACE) windowSurface else pbuffer
        EGL14.eglMakeCurrent(display, target, target, context)
        // Latched whether or not there is anywhere to draw it, or the texture
        // stops receiving frames.
        surfaceTexture.updateTexImage()
        latched = decoderFrames
        if (target == pbuffer || !decoderFrames) return
        draw()
    }

    /** Draws the latched frame into the output. On the GL thread, with the window surface current. */
    private fun draw() {
        EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)
        surfaceTexture.getTransformMatrix(transform)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        quad(program, transform)
        EGLExt.eglPresentationTimeANDROID(display, windowSurface, surfaceTexture.timestamp)
        EGL14.eglSwapBuffers(display, windowSurface)
    }

    /** The full-output strip through [shader], its texture already bound, coordinates through [texMatrix]. */
    private fun quad(shader: Int, texMatrix: FloatArray) {
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glUseProgram(shader)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(shader, "uTexMatrix"), 1, false, texMatrix, 0)
        val position = GLES20.glGetAttribLocation(shader, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, QUAD.position(0))
        val coord = GLES20.glGetAttribLocation(shader, "aTexCoord")
        GLES20.glEnableVertexAttribArray(coord)
        GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 16, QUAD.position(2))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun releaseOutput() {
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            EGL14.eglDestroySurface(display, windowSurface)
            windowSurface = EGL14.EGL_NO_SURFACE
        }
        reader?.let { old ->
            old.setOnImageAvailableListener(null, null)
            val out = synchronized(owners) {
                owners.values.any { it === old }.also { if (it) retired += old }
            }
            if (!out) old.close()
        }
        reader = null
        outWidth = 0
        outHeight = 0
    }

    /** Gives back an image [onImage] was handed. Safe from any thread, and for an image already given back. */
    fun release(image: Image) {
        val emptied = synchronized(owners) {
            val from = owners.remove(image)
            image.close()
            from?.takeIf { it in retired && owners.values.none { other -> other === it } }?.also { retired -= it }
        }
        emptied?.close()
        // A slot is free now: a frame left queued while there was none comes out.
        if (starved > 0) handler.post {
            if (closed || starved == 0) return@post
            starved--
            reader?.let(::take)
        }
    }

    private fun link(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun onThread(block: () -> Unit) {
        var failure: Throwable? = null
        val done = CountDownLatch(1)
        handler.post {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }
        check(done.await(5, TimeUnit.SECONDS)) { "the frame thread did not answer" }
        failure?.let { throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            onThread {
                releaseOutput()
                inputSurface.release()
                surfaceTexture.release()
                GLES20.glDeleteProgram(program)
                GLES20.glDeleteProgram(stillProgram)
                GLES20.glDeleteTextures(2, intArrayOf(texture, stillTexture), 0)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }.onFailure { Debug.trace("frame bridge teardown", it) }
        thread.quitSafely()
        pendingStill.getAndSet(null)?.first?.recycle()
        val leftover = synchronized(owners) {
            owners.clear()
            retired.toList().also { retired.clear() }
        }
        leftover.forEach(ImageReader::close)
    }

    companion object {
        /**
         * Images in the reader: the newest, the frame a consumer holds and the
         * two it drew last (the render thread can still be reading the older
         * one), and the next in flight.
         */
        const val MAX_IMAGES = 5

        // x, y, s, t per vertex: a full-screen strip in the standard GL
        // orientation. The SurfaceTexture's transform carries the flip between
        // GL's bottom-left origin and the image's top row, and any rotation the
        // codec attached, so nothing here turns the picture itself.
        private val QUAD: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
            it.put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f,
                ),
            )
        }

        private const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        // An uploaded bitmap's first row is t = 0, and the output's top is
        // y = 1: t taken as 1 - t puts the bitmap's top row at the top.
        private val FLIP_ROWS = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )

        private const val STILL_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
