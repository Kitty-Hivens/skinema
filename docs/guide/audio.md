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

## Playing through your own audio stack

The `sink` constructor parameter is the seam. Implement
`dev.hivens.skinema.audio.PcmSink` and the player pushes S16LE
interleaved stereo through it instead of opening a platform line -- an
adapter onto your own mixer, a socket, a server connection. No change is
needed on this side; the adapter is yours.

It only means anything with `audio = true`. A silent player decodes no sound,
so it never opens the sink at all -- passing one is not an error and not a
substitute for turning audio on.

```kotlin
interface PcmSink : AutoCloseable {
    fun open(sampleRate: Int)                                  // and starts it
    fun write(data: ByteArray, offset: Int, length: Int)       // blocking: this is the pacing
    fun stop()                                                 // freezes; framePosition holds
    fun start()
    fun flush()                                                // drop what is buffered (a seek)
    fun framePosition(): Long                                  // frames PLAYED since open
    fun setVolume(volume: Float)                               // linear 0..1, best effort
}
```

Two things carry the whole contract. `write` blocks until the device has
taken the bytes -- that is what paces playback, so a sink that accepts
everything instantly runs the decoder at its own speed. And
`framePosition` counts frames the device has *played*, not frames it has
accepted: it is the clock the player runs on, and a sink that reports
what it was handed makes the picture run ahead of the sound by a whole
buffer.

The calls do not all arrive on one thread. `open`/`write`/`stop`/
`start`/`flush` come from the audio thread in order; `close` can also
come from its watchdog, deliberately while a `write` is blocked, because
closing the line is how a dead device is broken out of; `setVolume`
comes from your thread; and `framePosition` is read from several threads
at once, including during a write. So `framePosition` and `setVolume`
must never wait on a lock the write holds -- every clock reader in the
player goes through `framePosition`, and one that parks there parks the
picture too.

## Device loss

If the audio device vanishes mid-playback (an unplug, a server
restart), a watchdog detaches the clock to wall time so the picture
keeps moving instead of freezing on a dead write.

Sound is not given up on. The audio thread then retries the device on a
fixed cadence (`SKINEMA_AUDIO_RECOVERY_MS`, 400 ms by default) for as
long as the outage lasts, and on its return resyncs to where the video
advanced on the wall clock and rebases the clock onto the fresh line, so
sound rejoins in step rather than lagging by the length of the outage.
The audio that played during it is dropped, not queued.

This is a safety net, not a routing system -- skinema does not follow a
default-device change; it keeps the video alive and takes the device
back if it comes back.
