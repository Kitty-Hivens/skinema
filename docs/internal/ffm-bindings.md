# FFM bindings and the native loader

skinema talks to FFmpeg (and libass) through Java's Foreign
Function and Memory API -- Panama. There is no JNI, no generated
binding monster. The whole layer is meant to be read in one sitting.

Key files, all in `skinema-core/.../libav/`:

| File              | Role                                                            |
|-------------------|----------------------------------------------------------------|
| `Libav.kt`        | the facade: loads each library, binds ~90 downcalls and the upcalls, wraps them |
| `LibavLibrary.kt` | the soname enum -- one entry per pinned library + filename rule |
| `LibavAbi.kt`     | struct field offsets and enum constants for the pinned major   |
| `NativeBundle.kt` | unpacks the classifier jar into a fingerprint-keyed cache      |
| `AvioSource.kt`   | a custom AVIO source: two upcalls over a consumer's byte source |

The optional capability mirrors the same shape: `ass/Ass.kt` +
`AssAbi.kt`. There used to be a second, `webp/Webp.kt` + `WebpAbi.kt`,
until FFmpeg 9's own `webp_anim` decoder made libwebp unnecessary.

## Binding a function

Every library is loaded once into `Arena.global()` at startup, a
`SymbolLookup` per library. Each native function becomes a downcall
handle from a `FunctionDescriptor`:

```kotlin
private fun fn(lib: LibavLibrary, name: String, descriptor: FunctionDescriptor): MethodHandle {
    val symbol = lookups.getValue(lib).find(name).orElseThrow {
        UnsatisfiedLinkError("${lib.fileName(Os.current())} exports no '$name'")
    }
    return linker.downcallHandle(symbol, descriptor)
}

private val hAvOptSet = fn(
    LibavLibrary.AVUTIL, "av_opt_set",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
)

fun avOptSet(obj: MemorySegment, name: MemorySegment, value: MemorySegment): Int =
    hAvOptSet.invoke(obj, name, value, 0) as Int
```

Calls go through `MethodHandle.invoke` (adapting), not `invokeExact`. A
few calls per frame against a millisecond-scale decode make the
adaptation cost irrelevant; the M0 spike confirmed this and it has not
been revisited. The surface is roughly the avformat / avcodec / swscale
/ swresample / avfilter functions you would expect (open, find stream,
read frame, send packet, receive frame, seek, the alloc/unref/free
families, sws and swr context lifecycle, the dict and error helpers),
plus two families the decode path does not use: the muxer and encoder
side (`avcodec_find_encoder_by_name`, `avcodec_get_supported_config`,
`avformat_alloc_output_context2`, `avformat_write_header`,
`av_interleaved_write_frame`, `av_write_trailer`) and hardware decode
(`av_hwdevice_ctx_create`, the `av_hwframe_*` family,
`avcodec_get_hw_config`).

Struct access is minimized: anything reachable through a function goes
through the function. Direct offset reads are limited to the
unavoidable set.

## The offset oracle

FFmpeg structs are opaque pointers; skinema reads a handful of their
fields by byte offset. `LibavAbi.kt` holds those offsets as constants
for the pinned major (n9.0):

```kotlin
object Frame {
    const val DATA = 0L          // pointer to planes
    const val LINESIZE = 64L     // row strides
    const val WIDTH = 104L
    const val HEIGHT = 108L
    const val FORMAT = 116L      // pixel/sample format enum
    const val PTS = 136L
    const val COLOR_RANGE = 280L // AVCOL_RANGE_*
    const val COLOR_TRC = 288L   // AVCOL_TRC_* (transfer -- the HDR gate)
    const val COLORSPACE = 292L  // AVCOL_SPC_* (YUV matrix)
    const val SIZEOF = 424L
}
```

These are not guessed. `tools/layout-oracle.c` is a small C program
that `#include`s the pinned FFmpeg headers and prints `offsetof()` and
`sizeof()` for every struct field skinema reads:

```c
#define P(expr) printf("%-44s = %lld\n", #expr, (long long)(expr))
P(offsetof(AVFrame, colorspace));
P(sizeof(AVFrame));
```

It is **not** part of the build. You run it by hand against the headers
of the pinned major and transcribe its output into `LibavAbi.kt`.
`ass-oracle.c` does the same for `AssAbi.kt` (`webp-oracle.c` is still
in the tree with nothing left to feed). Bumping the FFmpeg pin means
re-running the oracle and
updating the tables -- and that PR runs the integration suite against
the new build on every OS (see [natives-build.md](natives-build.md)).

Offsets are dereferenced with `MemorySegment.get(type, offset)`, and
nested structs are walked with `.reinterpret(size)` to establish a
sized view before reading. A raw pointer read without `.reinterpret`
yields a zero-length segment and throws on first access -- one of the
known traps below.

## Upcalls, and the barrier every one of them needs

Four native callbacks point back into Kotlin, and they are the sharpest
edge in this layer:

| Upcall              | Where                        | What it does                          |
|---------------------|------------------------------|---------------------------------------|
| libass log          | `ass/Ass.kt`                 | a `MethodHandles.empty` stub, silences libass |
| `get_format`        | `Libav.chooseHwFormat`       | picks the hw surface during hwaccel negotiation |
| AVIO `read_packet`  | `AvioSource.kt`              | fills a native buffer from a consumer's source |
| AVIO `seek`         | `AvioSource.kt`              | repositions that source               |

**A Throwable unwinding out of an upcall with native frames below it takes
the JVM down without a stack trace.** Not an exception the caller sees: the
process. So every upcall carrying logic ends in a catch, and what it does
there is the design:

- `chooseHwFormat` answers `AV_PIX_FMT_NONE`, which avcodec reads as "no
  format is acceptable" and fails the decode. That is deliberately NOT a
  fallback to software -- one file fails closed instead of the process dying
  without a word. It got its barrier last, because "it cannot throw" was
  true rather than enforced.
- The AVIO pair stashes the throwable in `pendingError` and stops the
  demuxer. A consumer's byte source is exactly where an `IOException` is a
  normal failure, so the decode thread calls `throwIfFailed` after a read
  and resurfaces it as itself -- fail-closed, with the real cause.

The libass stub is the exception that proves the rule: it is
`MethodHandles.empty`, so there is no code to throw, and it never
dereferences the `va_list` it is handed.

One more thing `chooseHwFormat` records, because it cost a whole feature.
The surface to aim for is read off the CONTEXT, not off the calling thread.
A frame-threaded decoder negotiates on one of its own workers rather than on
the thread that opened the file, so a thread-scoped target is simply absent
when the upcall runs -- and absent means the software entry. Hardware decode
was being negotiated away on every open while the device sat there ready.

## Memory discipline

One confined `Arena` per decode session, owned by the decode thread,
used only for out-parameters, strings and dicts. Everything FFmpeg
allocates is released through its matching `av_*_free` function, never
through the Arena. The Arena frees only what skinema allocated.

## Known traps

Each was paid for once; do not rediscover them.

1. `AVERROR(x)` is a negative errno, and **errno values differ per OS**
   -- `EAGAIN` is 11 on Linux, 35 on macOS. The error constants are
   platform-dependent; get this wrong and the receive loop never
   terminates on macOS.
2. FFmpeg logs to stderr by default. `av_log_set_level(QUIET)` at init,
   rather than wiring a log upcall.
3. FFM pointer dereferences need `.reinterpret(size)`. A raw read
   yields a zero-length segment and an `IndexOutOfBoundsException` on
   first access.
4. A Throwable unwinding out of an upcall takes the **process** down,
   without a stack trace -- not the caller's frame, the JVM. Every upcall
   carrying logic ends in a catch; see the upcall section above for what
   each of them answers instead.

## Soname pinning

Pinned line: **FFmpeg n9.0.x, LGPL, shared.** `LibavLibrary` is the
single source of the soname majors:

| Library    | soname major |
|------------|--------------|
| avutil     | 61           |
| swresample | 7            |
| swscale    | 10           |
| avcodec    | 63           |
| avformat   | 63           |
| avfilter   | 12           |

```kotlin
enum class LibavLibrary(val baseName: String, val sonameMajor: Int) {
    AVUTIL("avutil", 61), SWRESAMPLE("swresample", 7), SWSCALE("swscale", 10),
    AVCODEC("avcodec", 63), AVFORMAT("avformat", 63), AVFILTER("avfilter", 12);

    fun fileName(os: Os): String = when (os) {
        Os.LINUX -> "lib$baseName.so.$sonameMajor"     // libavformat.so.63
        Os.MAC -> "lib$baseName.$sonameMajor.dylib"    // libavformat.63.dylib
        Os.WINDOWS -> "$baseName-$sonameMajor.dll"      // avformat-63.dll
    }
}
```

Libraries load in declaration order, so each is preceded by everything
it links against (avutil first; avfilter, which can link any of the
others, last). The library is loaded **by exact soname** -- the
unversioned symlink belongs to `-dev` packages and majors coexist on
real systems. After load, `avformat_version()` and siblings are checked
and a major mismatch is a clean refusal, not a compatibility dance. A
matching test pins the table.

## The native loader

`Libav` resolves a directory once, with this precedence:

1. `-Dskinema.libav.dir=...` (JVM property)
2. `SKINEMA_LIBAV_DIR=...` (environment)
3. `NativeBundle.deployIfBundled()` -- the unpacked classifier jar
4. the system loader's search path (development fallback)

`NativeBundle` reads classpath resources under
`dev/hivens/skinema/natives/<platform>/`, where the bundle's own
`index.txt` lists a content fingerprint (first line) and then the
files. It unpacks into a per-user cache (`~/.cache/skinema/natives` and
the platform equivalents) via a temp dir and an atomic rename to the
fingerprint directory, so concurrent processes race safely and an
upgrade gets a new fingerprint directory rather than overwriting
libraries another process has mapped.

## Preload order

Some libraries import others that the dynamic loader will not find by
itself. skinema preloads the dependency first so the bundled copy wins
by name or soname:

- **Windows MinGW runtime.** The pinned av* DLLs import `zlib1`,
  `libbz2-1`, `libiconv-2`, `liblzma-5`, `libwinpthread-1`. These ride
  in the bundle but are not themselves pinned; `Libav` preloads them
  before the av* set so a clean machine without them on PATH still loads.
- **libass.** Preloads fribidi (shared, LGPL -- it must not fold into
  libass), and on Windows also the freetype and harfbuzz DLLs, before
  libass itself.
- **libva on Linux.** `preferHostVaapi()` maps the host's `libva.so.2`
  and `libva-drm.so.2` before libavutil, deliberately preferring the
  host copy over the bundled one: libva dispatches to a driver whose ABI
  is versioned against it, and the driver on the machine belongs to the
  host's libva, not to ours. The bundled pair stays as the fallback for
  a machine that has none.

## Optional capabilities

`Ass` is optional. It builds its bindings in a
`runCatching { Bindings() }.getOrNull()`; if the library fails to load,
`Ass.available` is `false` and text subtitles refuse selection while
everything else plays on -- that flag is the documented way for a
consumer to ask, too. This is
also where the simplest of skinema's four FFM upcalls lives (the others
are above): libass logs to stderr unless a message callback is set, and NULL
is a no-op, so a `MethodHandles.empty` stub silences it without ever
dereferencing the `va_list` it is handed.
