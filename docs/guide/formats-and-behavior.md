# Formats and the behavior contract

## What it plays

|                 |                                                                                               |
|-----------------|-----------------------------------------------------------------------------------------------|
| Containers      | mp4/mov/m4a, webm/mkv, avi, MPEG-PS/TS, flv, asf/wmv, dv, RealMedia, ogg, mp3, flac, wav, gif, apng, webp, raw ac3/eac3 |
| Video           | H.264, HEVC, H.266/VVC, VP8, VP9 (incl. webm alpha), AV1; MPEG-1/2, MPEG-4 Part 2, VC-1, WMV 7-9, H.263, Theora, ProRes, DNxHD, FFV1, RealVideo, Cinepak, Indeo, VP6; MJPEG |
| Animated images | GIF, APNG, animated WebP                                                                      |
| Audio           | AAC, AC-3/E-AC-3, DTS, TrueHD, ALAC, Opus, Vorbis, MP1/MP2/MP3, FLAC, WMA (v1/v2/Pro), AMR, WavPack, Monkey's Audio, TTA, ADPCM, G.72x, RealAudio, ATRAC, GSM, WAV PCM -- the device clock masters A/V sync |
| Subtitles       | ASS/SSA, SRT, mov_text, WebVTT (libass-rendered); PGS, VobSub, DVB (bitmap); CEA-608/708 closed captions, read out of the video bitstream; external .srt/.ass |
| Pixels out      | RGBA8888, straight alpha, exact-pts pacing, BT.601/709/2020 matrix and range honored, PQ/HLG tone-mapped to SDR |

The supported set is exactly the trimmed FFmpeg whitelist plus libass
(see [../internal/natives-build.md](../internal/natives-build.md) for
the authoritative list). The legacy and broadcast formats ride the
`decode` and `full` tiers; the lean `core` tier carries the modern
essentials only. Animated WebP decodes through FFmpeg's own `webp_anim`
decoder -- the libwebp that used to carry it is no longer built or
shipped.

Decode is not all of it: `MediaWriter` encodes video and audio into
mp4/mov, mkv and webm, and sound on its own into .opus, .flac and .wav --
in software everywhere and on the GPU where VAAPI is available. Which
encoders a build carries is a property of its tier, and only the software
H.264/HEVC pair is GPL; see the "What it writes" table in the README.
What it is either way is offline by construction -- the bundled FFmpeg is
built `--disable-network`, so the library physically cannot perform any
I/O beyond the file you hand it.

HDR (PQ or HLG over BT.2020) is tone-mapped to SDR on the decode path,
so it no longer plays washed out. Driving an actual HDR display
(native-HDR passthrough) is out of scope.

## The behavior contract

These are the guarantees a consumer builds on. They hold at every
read-ahead depth and whether or not audio is enabled.

### Fail closed

A file the pipeline cannot handle becomes
`VideoPlayer.State.Failed(cause)` -- never a thrown constructor, never a
half-decoded garbage frame, never a hang. There is one error path and
no partial recovery. Your job is to show a fallback (a static image, a
placeholder) when the state turns `Failed`. Damaged, exotic or
adversarial files are expected to land here; that is the design, not a
bug.

### Drop late

The clock never waits for a slow consumer. If your render loop falls
behind, the player skips frames to stay on time rather than building
latency.

The mailbox is latest-wins, so a render loop that falls behind sees the
newest frame rather than a backlog of stale ones.

### It stops for a consumer that stops looking

A mailbox that was being read and then is not is noticed on its own --
sixty pictures published into it and not taken, over at least two
seconds -- and the player stops decoding and converting for it. Nothing
has to be said: a Compose window that goes off screen stops running a
frame clock, `VideoSurface` stops polling, and the player follows.

What stopping costs the timeline is the `unwatched` constructor
parameter's to say. `WhenUnwatched.Freeze` (the default) stops the clock
with the pictures and reads `Paused` while it lasts, which is what a
background wants; `WhenUnwatched.KeepTime` lets time run on and rejoins
the picture wherever it got to, which is what a live source wants. The
next `acquireFrame` undoes either. A consumer that would rather mark the
moment exactly calls `setPresenting`, and saying it once takes the
automatic notice out of play for good.

A player whose mailbox has never been read is not covered: it may be
feeding something that is not a screen, so the notice only applies after
a first read.

### Seeks answer immediately

An exact seek previews the target keyframe on screen while the
frame-precise landing decodes behind it. An inexact seek
(`exact = false`) lands on the keyframe outright -- picture and sound
together, position as coarse as the file's keyframe spacing. Skip
buttons want inexact; timeline scrubbing wants exact.
`stepForward`/`stepBackward` move a single frame and leave the player
paused on it.

### Two threads per player, four with sound

A decode thread fills a small frame queue; a pacer thread presents from
it. Sound adds two: an audio thread that owns the device clock, and a
watchdog that hands the clock to wall time when the device stops
consuming without saying so. A selected subtitle track adds a fifth.
Players are independent and self-synced: there is no global clock and no
in-process mixer (the OS audio server mixes streams). Play as many as
your CPU affords -- a desktop comfortably runs dozens of 1080p30
streams.

### Read-ahead is opt-in

`readAheadFrames` (default 1, clamped to 1..8) holds that many decoded
frames of inventory, so a decode stall does not stall the screen while
inventory lasts. Each step of depth costs one full RGBA frame of
memory -- 8.3 MB at 1080p. Backgrounds stay at 1; a player UI wants
3-5.

## Compatibility policy

Decode correctness for well-formed files in the formats above is a bug
when broken. Exotic, damaged or adversarial files get the fail-closed
treatment by design; compatibility issues beyond that are triaged at
the maintainer's discretion. The library is shaped by a background and
overlay renderer, not a general-purpose editor: when in doubt it
refuses a file rather than guessing.
