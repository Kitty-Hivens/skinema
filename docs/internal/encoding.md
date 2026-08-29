# Encoding and muxing

The inverse of [decoding.md](decoding.md): RGBA frames and S16LE stereo
PCM in, a muxed file out. Same bindings, same fail-closed discipline, same
confined arena. Files in `skinema-core/.../encode/`: `MediaWriter.kt` and
`Transcoder.kt`.

Nothing here had an internal page until now, which is worth stating
plainly: three milestones (M12 software encode, M13 GPU encode, M14
transcode) and about thirteen hundred lines were reachable only by reading
them.

## MediaWriter: the push session

Decoding pulls; encoding pushes, and the shape is the mirror image. Every
public call is `send` followed by `drain`:

```kotlin
fun writeFrame(rgba: ByteArray, ptsNanos: Long) {
    val v = video ?: throw LibavException("this MediaWriter has no video stream")
    v.send(rgba, ptsNanos)
    drain(v.codecCtx, v.streamIndex, MICROS_DEN, v.streamTbNum, v.streamTbDen, v.frameDurationMicros)
}
```

`send` reverse-converts and hands the frame to the encoder; `drain` pulls
every packet the encoder has ready, rescales its timing, and interleaves it
into the container. An encoder holds frames for reorder depth, so a `send`
usually produces no packet and occasionally produces several -- the loop is
the whole reason the two are separate.

### Nothing is assumed about an encoder

The pixel format, the sample format, the channel layout and the sample rate
are all read off what the encoder advertises and converted into, rather than
guessed. That is why `libx264rgb`, `prores`, `qtrle` and `flac` work without
being special-cased: the code never names a format it expects.

- **Video**: RGBA -> the encoder's input format through swscale
  (YUV420P for the software encoders, NV12 for the VAAPI pair).
- **Audio**: interleaved S16LE stereo -> the encoder's planar format and
  channel layout through swresample, then chunked to the encoder's frame
  size. Stereo is kept wherever the encoder takes it, so the ordinary case
  converts the format alone; one that takes only mono is given mono.

A rate or a format an encoder cannot take is refused BY NAME rather than as
a bare errno, because `avcodec_open2` answers both with the same EINVAL and
a caller cannot tell which from that.

The swscale context is built into a local and published at the end, not
assigned first. A throw in between -- the colourspace call -- otherwise left
a context standing whose source buffers had never been allocated, and the
next frame skipped the build and handed swscale a null. The decode side had
the same shape and the same fix.

### Geometry is checked before anything is allocated

Positive and EVEN, and `fps` positive. The even constraint belongs to the
encoders: every one any tier ships takes a chroma-subsampled format, and a
subsampler cannot halve an odd side. It is checked at `open` rather than
discovered at `avcodec_open2` because the refusal is then a sentence rather
than an errno.

These are argument checks and they raise `IllegalArgumentException`, not
`LibavException` -- worth knowing, because the guide tells consumers to
catch the latter around an encode.

### Timing, and the packet duration that was not there

Video timestamps come from the caller in nanoseconds and run on a
microsecond codec time base; audio time is the running sample count from the
first sample pushed, on a time base of the sample rate. `drain` rescales
both into the muxer's stream time base by hand -- `av_rescale_q` would pass
`AVRational` by value, which these bindings avoid.

The duration is the part that bit. libavcodec fills a packet duration for
audio and leaves it at ZERO for video. Passing that zero on made the muxer
derive interior durations from dts deltas and give the last sample none, so
mp4 wrote an edit list a frame short and flagged the final sample discard:
**every clip this writer produced came back one frame shorter than it was
given**, with the frame rate misreported to match. A configured frame
duration is substituted when the encoder leaves it at zero.

### finish(): one attempt at the trailer, ever

`finish` drains both encoders and writes the container index. The drains are
retryable. `av_write_trailer` is not, and the flag records the ATTEMPT
rather than the outcome:

```kotlin
if (!trailerWritten) {
    trailerWritten = true
    try { Libav.checkAv(Libav.avWriteTrailer(fmtCtx), "av_write_trailer") }
    catch (t: Throwable) { trailerFailure = t; throw t }
} else {
    trailerFailure?.let { throw it }
}
```

`av_write_trailer` deinitialises the muxer whether it returns success or
failure, so a second call reads private data the first one already freed --
and that arrives as a **SIGSEGV**, not a return code, so neither `checkAv`
nor a `runCatching` can hold it. Recording the outcome instead left exactly
that second call on the ordinary path: a trailer refused for a full disk,
and then `close()` -- which every `use` block runs -- taking the process
down with it.

A retry therefore gets the drains and is told the truth about the step that
cannot be repeated. Reporting success there was worse than the failure: the
caller freed the disk, called `finish()` again, was told the encode had
completed, and kept a container nothing opens.

### close(): the index a forgotten finish() would have lost

A writer closed without `finish` -- the `use` block that threw, the caller
who forgot -- used to leave a file with no trailer, which for mp4 means no
moov atom, which no player opens and nothing reported. `close` writes it
best-effort: whatever aborted the write may refuse this too, and a file
missing its undrained tail still beats one missing its index.

Two smaller things live in the same teardown. `avio_closep` is where the
last flush of the file happens and it can fail -- a full disk otherwise
reaches the caller as a short file with nothing said anywhere -- so its
result is traced rather than dropped, and traced rather than thrown because
`close` still has to free the rest. And the `pb` field is nulled afterwards:
`avio_closep` nulls the scratch pointer it was handed, not the field it came
from, and `avformat_free_context` dispatches into the muxer's own deinit,
which is entitled to look at `pb`.

### Hardware encode

Naming a GPU encoder (`h264_vaapi`, `hevc_vaapi`) is detected from the codec
descriptor and switches the path: the frame is reverse-swscaled to NV12,
uploaded to a fresh surface from a pool, and encoded there. The encoder's
input pixel format is then the SURFACE format, not the software one.

The pool does not grow, so it is sized for the reorder and async depth at
default settings; undersizing it stalls `av_hwframe_get_buffer`.

It is fail-closed by design. A device that cannot open, or an upload that is
refused, throws -- there is no quiet fallback to software, because a caller
who asked for the GPU asked for a reason and a silent fallback is a
performance cliff nobody is told about. That is the opposite of the decode
side's policy, and deliberately: `HwAccel.AUTO` promises a per-file
fallback, and an encoder was never asked for one.

### One thread owns the writer

The confined `Arena` means `open`, every write, `finish` and `close` belong
to the thread that called `open`. Anything else throws
`WrongThreadException` out of FFM rather than a `LibavException`, so a
`catch (e: LibavException)` around the encode does not see it. This bites
hardest in a coroutine that splits `open` from the writes across a
dispatcher boundary.

## Transcoder

The join between the halves: read a file through the decode side, write
another through `MediaWriter`. It is a class rather than a snippet in the
guide because the join carries two traps a consumer cannot see from
outside, and both are timing.

- **One origin.** The writer times video by the timestamp it is handed and
  audio by a running sample count from the first sample pushed. A source
  whose sound starts after its picture would come out with the tracks
  shifted apart, so the gap is padded with silence.
- **One cadence.** The muxer interleaves by timestamp and holds one
  stream's packets until the other catches up, so pushing a whole track and
  then the other queues the first in native memory without bound. Both go in
  timestamp order, a chunk at a time, audio leading.

Geometry comes from the source rather than the config -- this converts a
file, it does not resize one -- and a source that changes geometry
mid-stream is refused rather than written into a stream opened at the first
frame's size. Rotation is APPLIED rather than carried, because the writer
has no orientation tag and a silently sideways file is the worse answer.

`run()` is **one-shot**. A second run opened a fresh writer on the same
path, and `avio_open` truncates, so the valid short file a cancelled run had
produced was replaced -- while the first writer was orphaned by the same
field assignment, taking its confined arena, its contexts and its file
handle with it. Cancelling and retrying is a natural thing to try, so the
refusal names what to do instead: a cancelled transcode is finished rather
than paused, and the rest needs a new `Transcoder` and a new output.

`cancel()` sets a volatile and is safe from any thread, which is what makes
it the way to stop a run from a UI thread. It stops at the next frame and
still writes the trailer, so a cancelled run leaves a shorter file that
plays rather than a broken one.

**It re-renders; it does not copy streams.** Every frame leaves the decoder
as RGBA and enters the encoder as RGBA: two swscale passes and a chroma
generation apiece. That is stated in the class and the guide rather than
hidden, because a caller who already has the codec they want is better
served by not decoding at all, and that is the thing this cannot do.

## Where the write surface is verified

`docs/encode-claims.txt` joins the README's "What it writes" table to a
bundle manifest, checked by `tools/check-readme-formats.sh` in CI -- the
same script and the same two directions as the read side. An encoder can go
missing exactly as quietly as the mov_text decoder did, which is the failure
that produced the check.
