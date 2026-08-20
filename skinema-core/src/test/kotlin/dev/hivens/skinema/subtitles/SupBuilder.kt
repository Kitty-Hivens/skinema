package dev.hivens.skinema.subtitles

import java.io.ByteArrayOutputStream

/**
 * Synthesizes a minimal PGS stream (.sup): one display set showing a
 * solid rectangle, one clear set ending it. PGS has no encoder anywhere,
 * and committed binaries are banned -- building ~150 bytes of segments
 * in test code is the honest fixture (the sup demuxer and matroska
 * stream copy take it from there).
 */
internal object SupBuilder {

    private const val PCS = 0x16
    private const val WDS = 0x17
    private const val PDS = 0x14
    private const val ODS = 0x15
    private const val END = 0x80

    /**
     * A 320x240 screen with a 32x16 white rectangle at (10,20), shown at
     * [showMs], cleared at [clearMs].
     */
    fun build(showMs: Long, clearMs: Long): ByteArray =
        ByteArrayOutputStream().also { it.pair(showMs, clearMs, 0) }.toByteArray()

    /**
     * [count] show/clear pairs, one every [periodMs], each visible for
     * [visibleMs]. Built for the retention question: what a schedule
     * holds is only measurable against a stream that keeps producing.
     */
    fun buildMany(count: Int, periodMs: Long, visibleMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        for (i in 0 until count) {
            val show = i * periodMs
            out.pair(show, show + visibleMs, i * 2)
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.pair(showMs: Long, clearMs: Long, composition: Int) {
        val out = this

        // Display set: composition + window + palette + object + end.
        out.segment(showMs, PCS) {
            u16(320); u16(240); u8(0x10)
            u16(composition) // composition number
            u8(0x80) // epoch start
            u8(0) // no palette update
            u8(0) // palette id
            u8(1) // one object
            u16(0); u8(0); u8(0) // object 0, window 0, not cropped
            u16(10); u16(20)
        }
        out.segment(showMs, WDS) {
            u8(1); u8(0)
            u16(10); u16(20); u16(32); u16(16)
        }
        out.segment(showMs, PDS) {
            u8(0); u8(0) // palette 0, version 0
            u8(1); u8(235); u8(128); u8(128); u8(255) // entry 1: opaque white
        }
        out.segment(showMs, ODS) {
            val rle = ByteArrayOutputStream()
            repeat(16) {
                // A 32-run of color 1, then end-of-line.
                rle.write(0x00); rle.write(0x80 or 32); rle.write(0x01)
                rle.write(0x00); rle.write(0x00)
            }
            val data = rle.toByteArray()
            u16(0); u8(0) // object 0, version 0
            u8(0xC0) // first and last fragment
            u24(data.size + 4)
            u16(32); u16(16)
            raw(data)
        }
        out.segment(showMs, END) {}

        // Clear set: an empty composition.
        out.segment(clearMs, PCS) {
            u16(320); u16(240); u8(0x10)
            u16(composition + 1)
            u8(0x00) // normal case
            u8(0); u8(0)
            u8(0) // zero objects = clear
        }
        out.segment(clearMs, END) {}
    }

    private class Payload {
        val bytes = ByteArrayOutputStream()
        fun u8(v: Int) = bytes.write(v and 0xFF)
        fun u16(v: Int) {
            bytes.write((v ushr 8) and 0xFF)
            bytes.write(v and 0xFF)
        }
        fun u24(v: Int) {
            bytes.write((v ushr 16) and 0xFF)
            u16(v)
        }
        fun raw(data: ByteArray) = bytes.write(data)
    }

    private fun ByteArrayOutputStream.segment(ptsMs: Long, type: Int, fill: Payload.() -> Unit) {
        val payload = Payload().apply(fill).bytes.toByteArray()
        write('P'.code); write('G'.code)
        val pts90k = ptsMs * 90
        for (shift in intArrayOf(24, 16, 8, 0)) write(((pts90k ushr shift) and 0xFF).toInt())
        repeat(4) { write(0) } // dts
        write(type)
        write((payload.size ushr 8) and 0xFF)
        write(payload.size and 0xFF)
        write(payload)
    }
}
