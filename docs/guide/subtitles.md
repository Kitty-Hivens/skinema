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
player the size you rasterize at with `setSubtitleCanvasSize` -- post it
on every resize, the way `VideoSurface` does, so text is sized to the
display rather than the coded video.

`skinema-skiko` provides `SubtitleOverlayImage` to turn the overlay's
positioned, premultiplied-alpha patches into placed
`org.jetbrains.skia.Image`s, with the same close-the-previous discipline
as `VideoFrameImage` (see [compose.md](compose.md)).

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
