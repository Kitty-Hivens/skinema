# Decoding

How packets become RGBA frames and stereo PCM. This sits on top of the
bindings ([ffm-bindings.md](ffm-bindings.md)) and feeds the runtime
([threading-and-clocks.md](threading-and-clocks.md)).

Files in `skinema-core/.../libav/`: `VideoDecoder.kt`,
`AudioDecoder.kt`, `TempoFilter.kt`, `ToneMap.kt`, and the
`FrameSource` interface that fronts them.

## FrameSource

`FrameSource` is the decode-side abstraction the player talks to. It is
an interface so the runtime can be tested with a `ScriptedFrameSource`
that needs no natives (see [testing.md](testing.md)). The real
implementations are `VideoDecoder` and `WebpAnimSource`;
`FrameSources.open(path)` picks between them (RIFF/WEBP to libwebp,
everything else to libav).

Its load-bearing methods:

```kotlin
fun nextFrame(target: ByteArray? = null, convert: Boolean = true): VideoDecoder.RgbaFrame?
fun convertLast(target: ByteArray? = null): VideoDecoder.RgbaFrame
fun seekTo(ptsNanos: Long)
```

`nextFrame(convert = false)` decodes a frame but does not run swscale --
it yields pts and dimensions over an empty buffer. `convertLast`
materializes the pixels of the most recent decode. This split lets the
pacer's catch-up runs decode forward at bare-decode cost and convert
only the frame they actually land on (see the late policy in the
runtime page). The defaulted methods (`durationNanos`, `tags`,
`chapters`, `coverArt`, `rotationDegrees`, `subtitleTracks`,
`videoSize`) carry container metadata.

## VideoDecoder: the pull session

Decoding is a pull loop over `receive_frame`, feeding it packets only
when it asks:

```kotlin
override fun nextFrame(target: ByteArray?, convert: Boolean): RgbaFrame? {
    while (true) {
        when (val ret = Libav.avcodecReceiveFrame(codecCtx, frame)) {
            0 -> return if (convert) convertCurrentFrame(target) else metadataOnlyFrame()
            LibavAbi.AVERROR_EAGAIN -> feedOnePacket()
            LibavAbi.AVERROR_EOF -> return null
            else -> Libav.checkAv(ret, "avcodec_receive_frame")
        }
    }
}
```

`feedOnePacket` reads packets with `av_read_frame`, skips those not on
the video stream, sends ours to the decoder, and on input EOF sends a
NULL flush packet so the decoder drains. The whole format zoo collapses
at one swscale chokepoint: decoded YUV becomes RGBA8888 in
`convertCurrentFrame`. The swscale and destination buffers are reused
across frames (allocating per frame would churn the GC), and the
destination array can be the caller's `target` when it is the right
size -- that is how the pacer hands a frame straight into the mailbox
slot with no copy.

### Decoder selection

`pickDecoder` swaps in libvpx for VP8/VP9, because the native vp8/vp9
decoders silently drop the webm alpha side-channel:

```kotlin
val libvpxName = when (codecpar.get(JAVA_INT, LibavAbi.CodecParameters.CODEC_ID)) {
    LibavAbi.AV_CODEC_ID_VP8 -> "libvpx"
    LibavAbi.AV_CODEC_ID_VP9 -> "libvpx-vp9"
    else -> return defaultDecoder
}
```

AV1 decodes through dav1d (the native AV1 decoder is too slow for
1080p). The codec context is opened with `threads = auto` via one
`av_opt_set` downcall -- the default is single-threaded, which put a
5.5s AV1 keyframe gap at ~1.5s of seek landing; threading cuts that
roughly threefold.

### Attached-picture refusal

A file whose only "video" stream is the embedded cover (an mp3 or flac
with art) would otherwise play the cover as a one-frame video and
report Ended while the sound ran on. `VideoDecoder.open` refuses an
attached-picture-only stream so such files go frameless, with the art
exposed as `coverArt` bytes.

### Rotation

`displayRotationDegrees` reads the display-matrix side data.
`av_display_rotation_get` reports the matrix's counterclockwise angle;
display applies the inverse, so the result is negated and snapped to
the quarter grid (0/90/180/270). The pixels are never touched -- the
rotation is a draw-time transform in `VideoSurface`.

## Colorspace and range

swscale's silent default is BT.601 / limited range for everything, so
every BT.709 (HD) file decoded through the wrong matrix (a small but
measurable shift, strongest in saturated greens) and full-range streams
came out with crushed levels. `ensureColorspaceDetails` reads each
frame's declared `colorspace` (YUV matrix) and `color_range` and calls
`sws_setColorspaceDetails` when they change. Untagged streams take the
convention players agree on: HD geometry means BT.709, smaller means
BT.601. Non-YUV sources (paletted gif, rgba apng) refuse the details
call and keep swscale's defaults, which is correct there.

## HDR tone-mapping

swscale converts the matrix and range but never the transfer function,
so PQ and HLG content needs an explicit tone-map or it plays washed
out. The path, gated in `VideoDecoder` and implemented in `ToneMap.kt`:

1. Gate on the frame's transfer characteristics (`COLOR_TRC`, offset
   288), **not** the colorspace -- gating on BT.2020 would wrongly
   catch wide-gamut SDR. PQ is `AVCOL_TRC_SMPTE2084`, HLG is
   `AVCOL_TRC_ARIB_STD_B67`.
2. A second swscale context converts to 16-bit `AV_PIX_FMT_RGBA64LE`
   (with its own `setColorspaceDetails`), into a dedicated `hdrOutHeap`
   kept separate from the SDR path's 8-bit buffer (sharing it caused a
   native over-read on an SDR/HDR geometry switch).
3. `ToneMapper` inverts the transfer to linear light (ST.2084 EOTF for
   PQ, the BT.2100 inverse-OETF plus the luma-dependent OOTF for HLG),
   applies an extended-Reinhard highlight knee at BT.2408 diffuse white
   (203 nits), converts BT.2020 to BT.709, and sRGB-encodes to 8-bit.
4. If the 16-bit context fails to build, the path falls back to the
   plain 8-bit conversion.

The hot loop is LUT-driven -- a 65536-entry input table, a small output
table, the Reinhard knee, one 3x3 matrix multiply -- so there is no
`pow()` per pixel and no native filter (zimg, libplacebo) and no GPU.
The consumer still receives plain SDR RGBA8888. The colour math is pure
top-level functions (like `swsCoefficientsFor`) so the tests hit it
directly against published ST.2084 / BT.2100 / sRGB reference points.

The aesthetic -- how bright HDR grades read -- lives in two knobs in
`ToneMap.kt`, `DIFFUSE_WHITE_NITS` and `MASTER_PEAK_NITS`. The maths
are correctness; those two are taste.

## AudioDecoder

Audio decodes through the same pull loop and resamples to stereo S16LE
at the source sample rate (`swr` rebuilds only when the format, rate or
channel count changes). Multichannel downmixes to stereo. `openOrNull`
returns `null` rather than throwing when a file has no audio stream -- a
silent file is not a failure. `enumerateTracks` walks every audio
stream for the `AudioTrack` list (language and title from the metadata
dict, channels, rate, default disposition), and `openOrNull(path,
streamIndex)` opens a specific track for live switching.

## TempoFilter: playback rate

Pitch-preserving rate change is FFmpeg's atempo, behind hand-written
avfilter bindings (the pin's sixth library; the trim carries exactly
`atempo` plus its `abuffer`/`abuffersink` endpoints). `TempoFilter`
wraps the graph: `process` pushes PCM in and reads stretched PCM out,
`flush` drains at EOF, `reset` rebuilds the graph. At tempo 1.0 the
filter does not exist and PCM flows untouched. The graph's transient
strings are allocated from a per-build confined arena, reclaimed on
every rebuild (a rate change, a seek, a loop) so scrubbing does not
leak. It lives on the audio thread's single write path -- see the
runtime page for why that placement matters to A/V sync.
