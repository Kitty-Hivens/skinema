# Encoding

`dev.hivens.skinema.encode.MediaWriter` is the inverse of the player:
you push RGBA8888 frames (and optionally S16LE stereo PCM) and it
encodes and muxes them into a file. Same bindings, same fail-closed
discipline -- any libav refusal throws `LibavException`, and `close()`
still releases everything.

The encoders live in the `full` native tier. `core` and `decode` carry
none, so a bundle check belongs in your build, not at runtime.

```kotlin
MediaWriter.open(
    Path.of("out.mp4"),
    VideoEncodeConfig("libx264", width = 1280, height = 720, fps = 30),
).use { writer ->
    repeat(90) { i ->
        writer.writeFrame(rgba, ptsNanos = i * 33_333_333L)
    }
    writer.finish()
}
```

The muxer is inferred from the extension: mp4/mov, mkv and webm.

## Configuration

```kotlin
VideoEncodeConfig(
    codecName: String,                        // an FFmpeg encoder name
    width: Int,
    height: Int,
    fps: Int,                                 // rate-control hint, not a hard cadence
    bitRate: Long = 0,                        // 0 leaves the encoder on its quality default
    options: Map<String, String> = emptyMap(),// the encoder's private options
    device: String? = null,                   // a GPU render node, for a hardware encoder
)

AudioEncodeConfig(
    codecName: String,
    sampleRate: Int,                          // input and output rate; nothing resamples
    bitRate: Long = 0,
    options: Map<String, String> = emptyMap(),
)
```

Nothing is assumed about what an encoder takes. The pixel format and the
sample format are both negotiated -- the writer asks the encoder what it
accepts and converts into that -- so `libx264rgb`, `prores`, `qtrle`,
`flac` and `libopus` work without being special-cased. A sample rate the
encoder does not support is refused by name rather than as a bare errno.

## Timing, which is the part that bites

Video timestamps are yours: `writeFrame(rgba, ptsNanos)` takes the
position on whatever timeline you are building, and frames must arrive
in non-decreasing order.

Audio timestamps are not. `writeAudio(pcm)` has no time argument -- the
audio track's time is the running sample count from the first sample you
pushed, so it always starts at zero.

**The two must therefore share an origin.** If you re-encode a segment
that begins five seconds into a source and pass the source's absolute
`ptsNanos`, the video track starts at five seconds and the audio at
zero, and the result plays with a permanent five-second offset that
nothing reports. Rebase your video timestamps to zero.

Equal timestamps are not safe either, whatever "non-decreasing"
suggests: mp4/mov reject a packet whose dts does not advance, and
timestamps are carried at microsecond resolution, so two frames less
than a microsecond apart collapse into one tick. Matroska tolerates it
and writes a zero-duration frame instead. Give every frame its own time.

## Pushing both streams

`writeFrame` and `writeAudio` are independent, and the muxer interleaves
by dts -- which means it holds packets from one stream until the other
catches up. Push them roughly in step.

Pushing ten minutes of video and only then the audio is not a slow path,
it is an unbounded one: libavformat queues the entire video elementary
stream in native memory, outside the JVM heap, waiting for the first
audio packet. The mirror case is the same. A writer opened with an
`AudioEncodeConfig` whose caller never calls `writeAudio` at all has the
same shape.

## Finishing

```kotlin
fun finish()    // drain both encoders, write the container index
fun close()     // release everything; writes the index if finish() did not
```

`finish()` is what completes the file. `close()` alone leaves a
best-effort attempt at the index -- better than nothing for a `use`
block that threw, but not the intended path.

`finish()` is retryable in its encoder drains and not in its last step:
`av_write_trailer` tears the muxer down whether it succeeded or failed,
so it is attempted exactly once, ever. If that attempt fails -- a full
disk is the realistic case -- the failure is what every later `finish()`
reports, because the container has no index and no second attempt can
give it one. Freeing the disk and retrying will not save that file.

## Hardware encode

Naming a GPU encoder (`h264_vaapi`, `hevc_vaapi`) switches the path: the
frame is converted to NV12, uploaded to a surface from a GPU pool, and
encoded there. `device` picks the render node; null takes the driver's
default.

It is fail-closed. If the device cannot be opened or an upload is
refused, the writer throws -- it does not quietly fall back to software,
because a caller who asked for the GPU asked for a reason and a silent
fallback is a performance cliff nobody is told about.
