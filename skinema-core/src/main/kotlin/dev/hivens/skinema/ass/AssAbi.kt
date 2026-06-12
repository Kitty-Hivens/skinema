package dev.hivens.skinema.ass

/**
 * Struct offsets and constants for the pinned libass major (soname 9),
 * captured by tools/ass-oracle.c compiled against that line's headers.
 * Do not edit by hand -- re-run the oracle on a major bump.
 */
internal object AssAbi {

    object Image {
        const val W = 0L
        const val H = 4L
        const val STRIDE = 8L

        /**
         * 1 byte of coverage per pixel. The guaranteed allocation is
         * exactly stride * (h - 1) + w: the last row may be unpadded,
         * and bytes past w in any row may be uninitialized.
         */
        const val BITMAP = 16L

        /** RGBA; the low byte is INVERTED alpha (0 = opaque). */
        const val COLOR = 24L
        const val DST_X = 28L
        const val DST_Y = 32L
        const val NEXT = 40L
        const val SIZEOF = 56L
    }

    const val FONTPROVIDER_AUTODETECT = 1

    /** 0.15.0 -- the first soname-9 release; everything bound here predates it. */
    const val VERSION_FLOOR = 0x01500000
}
