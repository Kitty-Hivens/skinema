# skinema developer documentation

Video decode and playback for JVM desktop apps: FFmpeg through
hand-written Java FFM (Panama) bindings, frames out as raw RGBA or Skia
images, a Compose Desktop surface on top. No JNI wrapper stacks, no
embedded player engines, no network access.

This tree has two audiences. Pick the half you need.

## Building on skinema (consumers)

You depend on `dev.hivens:skinema-*` and embed a player in your app.

- [guide/getting-started.md](guide/getting-started.md) -- dependencies,
  native bundles, the `--enable-native-access` flag, your first player.
- [guide/video-player.md](guide/video-player.md) -- the `VideoPlayer`
  API in full: lifecycle and state, frame acquisition, seeking, rate,
  frame stepping, metadata.
- [guide/compose.md](guide/compose.md) -- `VideoSurface`,
  `rememberPlayerState`, scaling and rotation, and how to draw frames
  yourself without Compose.
- [guide/audio.md](guide/audio.md) -- enabling sound, the
  audio-masters-the-clock model, volume, multiple audio tracks,
  playback rate.
- [guide/subtitles.md](guide/subtitles.md) -- embedded and external
  subtitles, text vs bitmap tracks, the optional libass capability.
- [guide/formats-and-behavior.md](guide/formats-and-behavior.md) --
  what plays, and the behavior contract every consumer relies on
  (fail-closed, drop-late, threading, read-ahead).

## Working on skinema (contributors)

You change the library itself. Read
[CONTRIBUTING.md](CONTRIBUTING.md) first, then the internals.

- [internal/architecture.md](internal/architecture.md) -- the module
  graph, the data path from file to pixel, and the design rules that
  shape every decision.
- [internal/ffm-bindings.md](internal/ffm-bindings.md) -- the Panama
  binding layer: downcall handles, struct-offset tables, the layout
  oracle, soname pinning, and the native loader.
- [internal/decoding.md](internal/decoding.md) -- the decoders:
  video pull-session and swscale, colorspace and HDR handling, audio
  resampling, and the playback-rate filter.
- [internal/threading-and-clocks.md](internal/threading-and-clocks.md)
  -- the per-player threads, the frame queue and pacer, the clock
  hierarchy, and how seeks stay synchronized.
- [internal/natives-build.md](internal/natives-build.md) -- the trimmed
  FFmpeg build, the bundle layout, the classifier jars, and the CI
  workflow that ships them.
- [internal/testing.md](internal/testing.md) -- the test philosophy,
  capability gating, the test doubles, and the diagnostic commands.

## ROADMAP.md is the source of truth

[`../ROADMAP.md`](../ROADMAP.md) is the project's working memory: every
architectural decision lands there together with the reasoning, in the
order it was decided. These docs are the structured, stable reference
on top of it. When the two disagree, the code wins, then ROADMAP.md,
then this tree -- and whichever is stale gets fixed.
