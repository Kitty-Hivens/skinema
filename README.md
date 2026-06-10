# skinema

Video decode and playback for JVM desktop apps. FFmpeg through hand-written
Java FFM (Panama) bindings, frames out as raw RGBA or Skia images, a Compose
Desktop surface on top. No JNI wrapper stacks, no embedded player engines,
no network access.

Status: pre-alpha. Nothing to consume yet. Every decision made so far, with
its reasoning, lives in [ROADMAP.md](ROADMAP.md) -- that file is the
project's working memory and the place to start reading.

## Layout

| Module            | Contents                                          | Floor   |
|-------------------|---------------------------------------------------|---------|
| `skinema-core`    | FFM bindings, demux/decode loop, frame pacing     | JDK 22  |
| `skinema-skiko`   | `VideoFrame -> org.jetbrains.skia.Image` (planned)| Skiko   |
| `skinema-compose` | `VideoSurface` composable (planned)               | Compose |

## License

Apache-2.0 for skinema itself. FFmpeg is consumed as separate LGPL shared
libraries, dynamically linked, never statically embedded; see the licensing
section of ROADMAP.md.
