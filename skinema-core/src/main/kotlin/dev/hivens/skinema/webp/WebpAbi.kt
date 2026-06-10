package dev.hivens.skinema.webp

/**
 * Struct offsets and ABI constants for libwebp's demux API, captured by
 * tools/webp-oracle.c against libwebpdemux soname major 2 (the
 * WEBP_DEMUX_ABI_VERSION dance: the public init/new entry points are
 * static-inline header wrappers that do not exist in the shared library;
 * the real exports take the ABI version as a trailing argument).
 */
object WebpAbi {

    const val DEMUX_ABI_VERSION = 263
    const val MODE_RGBA = 1

    object Data {
        const val BYTES = 0L
        const val SIZE = 8L
        const val SIZEOF = 16L
    }

    object DecoderOptions {
        const val COLOR_MODE = 0L
        const val USE_THREADS = 4L
        const val SIZEOF = 36L
    }

    object AnimInfo {
        const val CANVAS_WIDTH = 0L
        const val CANVAS_HEIGHT = 4L
        const val LOOP_COUNT = 8L
        const val FRAME_COUNT = 16L
        const val SIZEOF = 36L
    }
}
