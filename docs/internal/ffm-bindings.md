# FFM bindings and the native loader

skinema talks to FFmpeg (and libwebp, libass) through Java's Foreign
Function and Memory API -- Panama. There is no JNI, no generated
binding monster. The whole layer is meant to be read in one sitting.

Key files, all in `skinema-core/.../libav/`:

| File              | Role                                                            |
|-------------------|----------------------------------------------------------------|
| `Libav.kt`        | the facade: loads each library, binds ~40 downcalls, wraps them |
| `LibavLibrary.kt` | the soname enum -- one entry per pinned library + filename rule |
| `LibavAbi.kt`     | struct field offsets and enum constants for the pinned major   |
| `NativeBundle.kt` | unpacks the classifier jar into a fingerprint-keyed cache      |

Optional capabilities mirror the same shape: `ass/Ass.kt` + `AssAbi.kt`
and `webp/Webp.kt` + `WebpAbi.kt`.

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
families, sws and swr context lifecycle, the dict and error helpers).

Struct access is minimized: anything reachable through a function goes
through the function. Direct offset reads are limited to the
unavoidable set.

## The offset oracle

FFmpeg structs are opaque pointers; skinema reads a handful of their
fields by byte offset. `LibavAbi.kt` holds those offsets as constants
for the pinned major (n8.1):

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
`ass-oracle.c` and `webp-oracle.c` do the same for `AssAbi.kt` and
`WebpAbi.kt`. Bumping the FFmpeg pin means re-running the oracle and
updating the tables -- and that PR runs the integration suite against
the new build on every OS (see [natives-build.md](natives-build.md)).

Offsets are dereferenced with `MemorySegment.get(type, offset)`, and
nested structs are walked with `.reinterpret(size)` to establish a
sized view before reading. A raw pointer read without `.reinterpret`
yields a zero-length segment and throws on first access -- one of the
known traps below.

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

## Soname pinning

Pinned line: **FFmpeg n8.1.x, LGPL, shared.** `LibavLibrary` is the
single source of the soname majors:

| Library    | soname major |
|------------|--------------|
| avutil     | 60           |
| swresample | 6            |
| swscale    | 9            |
| avcodec    | 62           |
| avformat   | 62           |
| avfilter   | 11           |

```kotlin
enum class LibavLibrary(val baseName: String, val sonameMajor: Int) {
    AVUTIL("avutil", 60), SWRESAMPLE("swresample", 6), SWSCALE("swscale", 9),
    AVCODEC("avcodec", 62), AVFORMAT("avformat", 62), AVFILTER("avfilter", 11);

    fun fileName(os: Os): String = when (os) {
        Os.LINUX -> "lib$baseName.so.$sonameMajor"     // libavformat.so.62
        Os.MAC -> "lib$baseName.$sonameMajor.dylib"    // libavformat.62.dylib
        Os.WINDOWS -> "$baseName-$sonameMajor.dll"      // avformat-62.dll
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
  `libbz2-1`, `libiconv-2`, `libwinpthread-1`. These ride in the bundle
  but are not themselves pinned; `Libav` preloads them before the av*
  set so a clean machine without them on PATH still loads.
- **libass.** Preloads fribidi (shared, LGPL -- it must not fold into
  libass), and on Windows also the freetype and harfbuzz DLLs, before
  libass itself.
- **libwebp.** Preloads sharpyuv and libwebp before libwebpdemux, so
  the demuxer binds the bundled webp.

## Optional capabilities

`Ass` and `Webp` are optional. Each builds its bindings in a
`runCatching { Bindings() }.getOrNull()`; if the library fails to load,
`available` is `false` and the feature degrades (text subtitles refuse
selection; animated WebP falls back to the libav still path). This is
also where skinema's only FFM upcall lives: libass logs to stderr
unless a message callback is set, and NULL is a no-op, so a
`MethodHandles.empty` stub silences it without ever dereferencing the
`va_list` it is handed.
