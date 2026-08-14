# Contributing to skinema

skinema is a small, deliberately readable library. The bar is that a
person -- not a tool -- can hold the binding layer in their head in one
sitting and maintain it years from now. Keep that bar.

## Build and test

```
./gradlew build          # compile + all modules' tests
./gradlew :skinema-core:test
./gradlew compileKotlin   # quick compile check
```

Toolchain: build on **JDK 25** (the foojay resolver fetches one if it
is absent), emitting **JVM 22** bytecode -- `java.lang.foreign` went
final in 22 and the bindings need it. Pins live in `../ROADMAP.md`
section 12 and `gradle.properties`.

For integration tests you need the `ffmpeg` CLI on PATH (it generates
the fixtures) and a loadable FFmpeg matching the pinned major. On Linux
the system FFmpeg works if its sonames match section 4 of ROADMAP; to
test against the shipped bundle instead, point `SKINEMA_LIBAV_DIR` at a
local build (see [internal/natives-build.md](internal/natives-build.md))
or set `SKINEMA_REQUIRE_CAPS` to fail loudly on a missing capability
rather than skip. Tests skip (not fail) when the CLI or libraries are
absent, unless `SKINEMA_REQUIRE_CAPS` lists the capability.

## Run it

```
./gradlew :skinema-demo:run -Pvideo=<file> [-Psound] [-Psubs=<file>] [-PreadAhead=N]
```

The other harness commands (spike, seekbench, soak, harness) and their
flags are in [internal/testing.md](internal/testing.md). When you touch
the playback loop, bench 60fps content -- 24fps masks dead time in the
pacer. When you touch seeking, drive `:skinema-demo:seekbench` with
`SKINEMA_DEBUG_SEEK=1` and prove the fix with data, not feel.

## Where decisions live

`../ROADMAP.md` is the project's working memory. Every architectural
decision is there with its reasoning, in the order it was decided.
Before changing something that looks odd, read the relevant milestone
or section -- it usually guards a bug that was expensive to find. When
you make a decision that changes a recorded one, **edit the entry and
say why**; do not silently rewrite it. The `docs/` tree (this
directory) is the structured reference on top of ROADMAP; update it when
the behaviour it describes changes.

## Code style

This repo follows the project-wide engineering conventions; the ones
that matter most here:

- **ASCII in source, comments, KDoc, commit messages and these docs.**
  Use `--`, `->`, `<-`, plain words. Unicode only where an end user
  reads the exact string. No emoji anywhere.
- **Comment the non-obvious why, once, and let names do the rest.** No
  comment on a mechanical change -- git blame and the commit message
  carry it. No restating what the code already says. The binding layer's
  comments earn their place by explaining a trap, not narrating a call.
- **Import symbols; no fully-qualified inline paths** at call sites.
- **No process metadata** in commits, comments or docs -- no dates, PR
  numbers, "previously", or how a change was produced. State what and
  why, grounded in the code.

## Commits and PRs

- Plain, factual, present-tense commit subjects. Add `Closes #N` only
  for a real issue the commit resolves.
- A PR that changes the native surface must follow the push-sequencing
  rule in [internal/natives-build.md](internal/natives-build.md):
  natives first, wait for every affected rolling-release asset, then the
  consuming code. Push the code first and CI goes red on every
  platform.
- A PR that bumps the FFmpeg pin re-runs the layout oracle, updates the
  ABI and soname tables, rebuilds all platforms, and lands as one
  change CI can verify on every OS.

## Touching the bindings

If you add a downcall: bind it in `Libav.kt` with a `FunctionDescriptor`
and a thin wrapper, near its siblings. If you read a new struct field:
add the offset to `LibavAbi.kt` (or `AssAbi`/`WebpAbi`) from the
oracle's output -- run `tools/layout-oracle.c` against the pinned
major's headers; do not guess an offset. Respect the memory discipline:
one confined arena per session, FFmpeg allocations freed through their
`av_*_free`, the arena only for out-params, strings and dicts. The
known traps in [internal/ffm-bindings.md](internal/ffm-bindings.md) are
non-negotiable.

## Releasing

The maintainer cuts releases. Libraries and natives release separately --
they are on separate version lines (ROADMAP M17), and a library release must
never re-upload the ~211 MiB of platform bundles.

Both go out through the `Publish` workflow (vanniktech maven-publish,
auto-release to Maven Central, GPG-signed). It runs in the `maven-central`
environment, so the credentials sit behind that environment's approval.

A library release:

1. Make sure the natives the release needs are already published (see below)
   and on the rolling `natives-<ffmpeg version>` release (the
   push-sequencing rule). The workflow checks the published half itself and
   refuses a release whose natives are not up.
2. Tag `vX.Y.Z` on the release commit and push the tag -- that is the
   trigger. The workflow reads the version from the tag, refuses one that
   already exists on Central (versions are immutable), and runs
   `publishLibraries`, which ships core/skiko/compose only. The bare
   `publishToMavenCentral` sweeps in the natives; nothing calls it.
3. Create the GitHub release with consumer-facing notes, naming the natives
   version consumers should pair with it.

A natives release, only when the bundles actually change (a new FFmpeg pin,
a repack that fixes what a bundle carries):

1. Bump `nativesVersion` in `gradle.properties`: the FFmpeg version of the
   build, plus a revision that increments for repacks of that same build
   (`9.0.1-1` -> `9.0.1-2`; a new pin resets it, `9.1.0-1`). Central versions
   are immutable, so changed bytes always need a new number -- reusing one is
   rejected.
2. Dispatch `Publish` with target `natives`. It refuses a version already on
   Central, fails on any of the 24 tier/platform assets missing from the
   rolling release, and logs each asset's `updated_at` -- read them and
   confirm they are the build you mean to ship before approving the
   environment.

Publishing from a workstation stays available for when the workflow is not
an option -- same tasks, `-PappVersion=X.Y.Z` for the libraries, and
`--no-configuration-cache` on both. Signing then goes through the gpg agent
against the key in the local keyring (`signing.gnupg.keyName`) instead of
the CI path.

The workflow needs four repository secrets: `MAVEN_CENTRAL_USERNAME` and
`MAVEN_CENTRAL_PASSWORD` (a Central Portal user token, not the account
login), plus `SIGNING_KEY` (the ASCII-armored secret key) and
`SIGNING_KEY_PASSWORD`. The root build applies the in-memory key when it is
present and falls back to the keyring when it is not.

`skinema-core`/`-skiko`/`-compose` publish as libraries; `skinema-natives`
publishes an empty main jar with the 24 tier/platform bundles attached as
classifiers.

## License

Apache-2.0 for skinema. FFmpeg and the other native dependencies are
consumed as separate dynamically-linked shared libraries (LGPL where it
applies), never statically embedded into a shipped skinema artifact;
their license texts ride in every natives bundle. Keep it that way -- a
static link would change the licensing story.
