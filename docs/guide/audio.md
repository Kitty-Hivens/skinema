# Audio

Sound is off by default. Pass `audio = true` to play it:

```kotlin
val player = VideoPlayer(Path.of("clip.mkv"), audio = true)
```

With sound on, aac, ac3/eac3, alac, opus, vorbis, mp3, flac and WAV PCM
(16/24/32-bit and float) decode through the same bindings.
Multichannel audio downmixes to stereo. Audio-only files (an mp3, a
flac) play frameless through the normal lifecycle -- no frames, just
sound and metadata.

## The clock model

This is the one thing worth understanding before you build UI on top.

When a player has sound, **the audio device becomes the clock**. A DAC
consumes samples at its own fixed rate, so the only honest measure of
"where are we" is how many samples it has played. Video follows that
clock; it never drives it. A silent player runs on a wall-time clock
instead.

The practical consequences:

- `positionNanos()` tracks the audio device, not a wall timer.
- Seeks freeze the sound at the target, let the video land against
  standing time, then restart -- so audio and video arrive together
  instead of one chasing the other.
- A loop restarts both sides together, once the picture has played out
  the file's own duration; the sound plays once per lap rather than
  looping inside one.

You do not manage any of this; it is why sound and picture stay in sync
through seeks, pauses, rate changes and track switches. See
[../internal/threading-and-clocks.md](../internal/threading-and-clocks.md)
if you want the mechanism.

## Volume

```kotlin
fun setVolume(volume: Float)   // linear 0..1
```

Per-player linear gain. There is no global mixer in skinema -- each
player owns its own audio stream and the OS audio server (PipeWire,
WASAPI, CoreAudio) mixes them. Cross-source ducking and master volume
are your application's concern.

## Multiple audio tracks

A container with several audio streams exposes them, and you can switch
in place without interrupting the picture:

```kotlin
val tracks: List<AudioTrack> = player.audioTracks
val active: Int? = player.activeAudioTrack    // stream index now playing

fun selectAudioTrack(streamIndex: Int)
```

`AudioTrack` carries `streamIndex`, `language` (ISO 639 tag or `null`),
`title` (or `null`), `channels`, `sampleRate`, and `isDefault` (the
container's default-track flag). Build a track menu from it.

`audioTracks` is published only once a live audio pipeline exists -- a
machine with no audio device enumerates nothing to advertise. Start on
a specific track with the `audioTrack` constructor parameter, or switch
later with `selectAudioTrack`. A switch re-anchors the sound at the
current playhead and keeps the video running. An unopenable track is
refused and the current one keeps playing -- a failed switch means
"nothing changed."

## Playback rate

```kotlin
fun setRate(rate: Float)   // 0.5x .. 4.0x, pitch preserved
```

The rate runs through FFmpeg's atempo filter on the audio path, so the
pitch stays natural. With sound on, the audio clock advances at the new
rate and the picture follows; on a silent player the wall clock scales
instead. The rate survives seeks, pauses and track switches.

## Device loss

If the audio device vanishes mid-playback (an unplug, a server
restart), a watchdog detaches the clock to wall time so the picture
keeps moving instead of freezing on a dead write. This is a safety net,
not a routing system -- skinema does not follow the default-device
change; it keeps the video alive.
