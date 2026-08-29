# Subtitles

Subtitles are off by default. Nothing subtitle-related runs until you
select a track -- the pipeline starts lazily on the first select.

## Enumerate and select

```kotlin
val tracks: List<SubtitleTrack> = player.subtitleTracks
val active: Int? = player.activeSubtitleTrack    // id on screen, null when off

fun selectSubtitleTrack(id: Int?)    // null turns subtitles off
```

`SubtitleTrack`:

```kotlin
class SubtitleTrack {
    val id: Int             // selection handle (stream index for embedded, negative for external)
    val streamIndex: Int
    val language: String?
    val title: String?
    val codecName: String   // "ass", "subrip", "mov_text", ... as libav reports
    val isText: Boolean     // text (needs libass) vs bitmap (pgs, dvdsub)
    val isDefault: Boolean
    val isForced: Boolean   // forced tracks translate on-screen foreign text only
    val externalPath: Path? // null for embedded; the source file for external
}
```

Pass `track.id` to `selectSubtitleTrack`, and `null` to turn subtitles
off again.

## Closed captions (CEA-608/708)

Captions are not a stream, and that is the whole of what makes them different
here. They ride as ATSC A53 payload inside the H.264/HEVC bitstream, so the
container cannot be asked whether a file has them -- only a decoded frame can
answer.

So the track appears **when the first captions are seen**, not at open:

```kotlin
val captions = player.subtitleTracks.firstOrNull { it.codecName == "eia_608" }
```

Poll for it rather than reading `subtitleTracks` once. A file that carries
captions grows the track within a frame or two of playback starting; a file
that does not never grows one, which is the point -- nothing is advertised
that would render an empty screen. It is the same shape as `durationNanos` on
an animated webp, which is also only knowable by decoding.

Once it is there it selects and renders like any text track: `cc_dec` produces
ASS, so libass draws it and the overlay reaches you through
`acquireSubtitles` exactly as an SRT track would. It needs libass for the same
reason, and the `cea608` capability names the decoder itself.

What is not covered: TTML, SMPTE-TT and DFXP. FFmpeg has a TTML encoder and no
TTML decoder, so reading one would mean a parser of our own rather than a
whitelist entry.

## Text vs bitmap

- **Text tracks** (ASS/SSA, SRT, mov_text, WebVTT, `isText == true`)
  render through libass with full typesetting, including fonts embedded
  in an mkv. libass is an optional capability (below).
- **Bitmap tracks** (PGS, VobSub, `isText == false`) decode to pixels
  directly and need no libass at all.

Text rendering uses the system's fonts (fontconfig on Linux,
DirectWrite on Windows, CoreText on macOS) plus any fonts the container
embeds. A headless box with no fonts renders blank overlays.

## External subtitle files

```kotlin
fun addExternalSubtitles(file: Path): List<SubtitleTrack>
```

Probe a `.srt` or `.ass` file and append its track(s) to
`subtitleTracks` under negative ids; the returned list is what was
added. They sit on the same timeline as the video and select the same
way. A frameless player takes none; an unreadable file returns an empty
list (it does not throw).

## Drawing them

With Compose, `VideoSurface` composites the overlay for you -- glued to
the picture, upright through rotation, and rasterized at window size so
glyphs stay crisp. You do nothing beyond selecting a track.

If you draw frames yourself, poll the overlay alongside the frame:

```kotlin
fun acquireSubtitles(): SubtitleOverlay?       // same contract as acquireFrame
fun setSubtitleCanvasSize(width: Int, height: Int)
```

`acquireSubtitles` returns the newest overlay, or `null` for "nothing
newer." Gate drawing on `activeSubtitleTrack` being non-null. Tell the
player the size you rasterize at with `setSubtitleCanvasSize`, the way
`VideoSurface` does, so text is sized to the display rather than the
coded video.

`setSubtitleCanvasSize` is idempotent, so posting it from a draw loop is
fine: the same size twice costs a comparison and queues nothing. That was
worth fixing rather than documenting -- it used to compare on the subtitle
thread, after that thread had been woken to read the command, so a steady
window handed an unbounded queue sixty announcements a second and the pump,
which reads a non-empty queue as work pending, refilled a packet at a time and
never reached its own render cadence. `VideoSurface` posts it unguarded now.

What comes back:

```kotlin
class SubtitleOverlay {
    val generation: Long                  // bumps on every publish -- cheap change detection
    val canvasWidth: Int                  // the space the coordinates below live in
    val canvasHeight: Int
    val patches: List<SubtitlePatch>      // EMPTY means clear the screen
}

class SubtitlePatch {
    val x: Int; val y: Int                // top-left, in canvas space
    val width: Int; val height: Int
    val rgba: ByteArray                   // premultiplied alpha, tight stride (width * 4)
}
```

Three things carry it. An overlay whose `patches` are empty is a
**clear**, not a "nothing happened" -- draw it, or the last cue stays on
screen forever. The coordinates are in the canvas space the overlay
announces, so map them onto wherever the video actually lands. And the
`rgba` array is **reused** across publishes of the same slot: it is
yours until your next `acquireSubtitles`, and anything you keep past
that you must copy.

`skinema-skiko` provides `SubtitleOverlayImage` to turn those positioned,
premultiplied-alpha patches into placed `org.jetbrains.skia.Image`s (see
[compose.md](compose.md)).

It keeps `VideoFrameImage`'s rule: one live borrow per side, so `update` may
run off the drawing thread and what `images` returns stays alive until that
thread reads it again. Read `images` once per draw and do not keep it. It used
to close every image it replaced on the spot, which quietly made `update` the
drawing thread's alone while the compose guide told you -- correctly, for
frames -- to raster elsewhere; a consumer generalising from one to the other
freed overlay pixels under a draw.

`close` is where the two differ, and deliberately: it frees what is held and
leaves the object usable, because turning subtitles off is a reason to drop
the pixels while the surface lives on, and the re-selection after it has to be
able to publish again.

## The libass capability

libass ships in the native bundles, but it is treated as optional. When
it is present, text tracks render. When it is absent (a stripped
bundle, a load failure), **text tracks refuse selection and everything
else plays on** -- video, audio and bitmap subtitles are unaffected.
Ask before you select:

```kotlin
dev.hivens.skinema.ass.Ass.available   // synchronous, no side effects
```

Selection is asynchronous -- `selectSubtitleTrack` queues a command for
the decode thread -- so watching `activeSubtitleTrack` for an answer is
a poll with no defined settling point. `Ass.available` answers straight
away. Bitmap subtitles never depend on libass, so preferring a bitmap
track where both exist is the other way out.
