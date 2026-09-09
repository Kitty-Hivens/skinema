# Audio

Sound is off by default. Pass `audio = true` to play it:

```kotlin
val player = VideoPlayer(Path.of("clip.mkv"), audio = true)
```

With sound on, aac, ac3/eac3, alac, opus, vorbis, mp3, flac and WAV PCM
(16/24/32-bit and float) decode through the same bindings. Audio-only
files (an mp3, a flac) play frameless through the normal lifecycle -- no
frames, just sound and metadata.

The sound reaches the device in the shape the file has it: six channels
stay six, twenty-four bits stay twenty-four, and the rate is never
converted. What the device will not take is folded, and only then. See
[The shape of the sound](#the-shape-of-the-sound).

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

To start quiet, pass the `volume` constructor parameter rather than calling
this straight after constructing. The audio thread opens the device and writes
its first chunk on its own schedule, so there is no moment after the
constructor that reliably beats it -- a player meant to fade in from silence
would let a chunk through at full.

```kotlin
val player = VideoPlayer(Path.of("clip.mkv"), audio = true, volume = 0f)
```

Either way the value sticks to the player, not to the line: every line it
opens is set to it, so a track switch or a device-loss recovery comes back at
the volume you asked for instead of at the device's default. Out-of-range
values are clamped, and `NaN` is refused outright rather than clamped --
every comparison with it is false, so a clamp passes it straight to a gain
control that accepts it and silences the line.

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

## The shape of the sound

```kotlin
val format: PcmFormat? = player.activeAudioFormat   // what the device took
```

The player offers the device the shape the file actually has and walks
down until the device takes one. Nothing is converted for the sake of the
seam: a 5.1 float file reaches a device that can take 5.1 float as 5.1
float, and the fold happens only where a device refuses.

```kotlin
data class PcmFormat(
    val sampleRate: Int,
    val channels: Int,
    val layout: String,          // "mono", "stereo", "5.1", "5.1(side)"
    val encoding: PcmEncoding,   // U8, S16LE, S32LE, F32LE, F64LE
    val significantBits: Int,    // how many bits of each sample are real
)
```

Three of those fields are worth a sentence each.

`layout` names the channel order rather than leaving it to be assumed. A
count says how many channels arrive and not where they go, and 5.1
against 5.1(side) is the ordinary pair that differs.

`significantBits` is not the width of the encoding. A 24-bit file decodes
into `S32LE` with eight zero bits below the value, and some DTS carries
twenty. A sink that has to narrow samples for its device needs this to
know whether it is discarding padding or data.

`encoding` has no 24-bit member, and that is a decision: FFmpeg has no
24-bit sample format, so 24-bit content arrives in `S32LE` whole. A device
that wants three packed bytes wants a narrower container, not more
information, and that packing belongs beside the device.

**Read it, do not assume it.** `activeAudioFormat` is what the device
agreed to, not what was asked for, which is the only version worth
having: on a stereo device a 5.1 file reports stereo.

`audioChannels` decides what gets asked for in the first place:

```kotlin
VideoPlayer(path, audio = true, audioChannels = ChannelPreference.Stereo)
```

`Source` is the default, on the same argument the sample rate is left
alone under: the audio server knows the speaker layout and this library
does not. `Stereo` folds before the device is asked, for a consumer that
knows the fold is wanted -- a device accepting six channels is not
evidence that six speakers exist.

## Playing through your own audio stack

The `sink` constructor parameter is the seam. Implement
`dev.hivens.skinema.audio.PcmSink` and the player pushes interleaved PCM
through it instead of opening a platform line -- an adapter onto your own
mixer, a socket, a server connection. No change is needed on this side;
the adapter is yours.

It only means anything with `audio = true`. A silent player decodes no sound,
so it never opens the sink at all -- passing one is not an error and not a
substitute for turning audio on.

```kotlin
interface PcmSink : AutoCloseable {
    fun open(format: PcmFormat)                                // and starts it; throws to refuse
    fun write(data: ByteArray, offset: Int, length: Int)       // blocking: this is the pacing
    fun stop()                                                 // freezes; framePosition holds
    fun start()
    fun flush()                                                // drop what is buffered (a seek)
    fun framePosition(): Long                                  // frames PLAYED since open
    fun setVolume(volume: Float)                               // linear 0..1, best effort
}
```

Three things carry the whole contract.

`write` blocks until the device has taken the bytes -- that is what paces
playback, so a sink that accepts everything instantly runs the decoder at
its own speed.

`framePosition` counts frames the device has *played*, not frames it has
accepted: it is the clock the player runs on, and a sink that reports
what it was handed makes the picture run ahead of the sound by a whole
buffer.

`open` is allowed to **refuse**, and refusing is how a sink says what it
can do. The player offers the file's own shape and walks down until one
is taken, ending at `PcmFormat.floor(rate)` -- S16LE stereo, which every
implementation must accept. Two rules follow: a refusal must leave
nothing playing, since the next call is another `open` on the same sink;
and it must be a throw rather than a quiet substitution, because a sink
that accepts 5.1 and plays the front two channels is indistinguishable
from one that works, and the player would never learn to fold the rest
itself. The rate is the media's own and is never converted by either
side.

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

An outage is waited out. The audio thread retries the device on a fixed
cadence (`SKINEMA_AUDIO_RECOVERY_MS`, 400 ms by default) for as long as
the outage lasts, and on its return resyncs to where the video advanced
on the wall clock and rebases the clock onto the fresh line, so sound
rejoins in step rather than lagging by the length of the outage. The
audio that played during it is dropped, not queued.

A device that will not take sound is a different case, and it is not
retried forever. A line that reopens cleanly and then refuses the write
after it is not an outage, and retrying one costs a clock rebased onto a
line that plays nothing every round. After three such rounds the audio
side gives up: the clock stays on the wall, the picture plays on, and
sound does not come back even if the device later does. Closing the
player and opening another is what recovers from that.

This is a safety net, not a routing system -- skinema does not follow a
default-device change; it keeps the video alive and takes the device
back if it comes back.
