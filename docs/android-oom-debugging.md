# Android OOM diagnostics (local development)

Core development branch: `codex/local-blend-filter-resolution` in `../ruffle`.
Keep `0.7.1-A` unchanged until device validation succeeds.

The ignored `.cargo/config.toml` patches the six direct Ruffle dependencies to
that checkout. Cargo.lock consequently contains local path packages during this
investigation. Do not publish this local lockfile as a release dependency update;
replace the patch with a tested core commit when integrating the change.

## Prepared environment

- Java 21; Gradle 9.5.1 (project wrapper)
- Rust 1.99.0 with `aarch64-linux-android`
- cargo-ndk 4.1.2
- SDK: `.local/android-sdk`, platform Android 36, build-tools 36.0.0
- NDK r27: `.local/android-sdk/ndk/27.0.12077973`
- `local.properties`: arm64 only, local SDK path

From the Android project root:

```sh
export ANDROID_HOME="$PWD/.local/android-sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
cargo ndk --target arm64-v8a --platform 26 check
./gradlew -PlocalBuild :app:assembleDebug --console=plain
```

The local application id is `rs.ruffle.kabu.local`. Use an explicit `adb -s`
serial, because wireless discovery may expose the same phone twice. Pairing
codes expire and are intentionally omitted from this document.

## Diagnostic interpretation

`perf.log` now includes a once-per-second `gpu-memory` snapshot with live bitmap
counts, the eight largest bitmap handles and dimensions, scratch ownership,
last-frame scratch peak, and blend/soft-blur optimization hits. These samples
are snapshots, not peaks over the whole second. Bitmap byte estimates exclude
GPU-only/in-flight references and driver residency; scratch idle budgets do not
limit total GPU memory.

`process-memory` records RSS, RSS high-water mark, anonymous/file RSS, swap and
thread count from `/proc/self/status`. The panic record also includes these
fields. RSS must not be treated as a complete GPU residency measurement.

Explicit previously unnamed dynamic/filter/Stage3D buffers now have purpose
and byte-size labels. Internally allocated wgpu staging buffers may still be unnamed. A buffer
OOM identifies the failing allocation; it does not alone identify the dominant
memory owner or prove a leak.

Baseline files pulled from the phone are under
`/tmp/ruffle-oom/device-baseline`; the four original diagnostic archives were
expanded under `/tmp/ruffle-oom/ruffle-diagnostics-*`.

## Preparation validation

- Core renderer `cargo check`: passed.
- Android arm64 `cargo ndk ... check`: passed with the local Ruffle patches.
- Gradle `preDebugBuild` and `debugRuntimeClasspath` resolution: passed.
- Kotlin and Android resource compilation (`compileDebugKotlin`): passed.
- APK assembly and on-device OOM reproduction remain separate validation steps.

## Device diagnostic APK

Build an independently installable diagnostic package with optimized native code:

```sh
export ANDROID_HOME="$PWD/.local/android-sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
./gradlew -PdiagnosticBuild :app:assembleRelease --console=plain
```

This variant uses `rs.ruffle.kabu.diagnostics`, label `游戏不卡（诊断）`, local
debug signing and a debuggable Android manifest. The Rust native library uses
the release profile. It can coexist with the existing game package without
changing its data. The flag must be supplied again for subsequent diagnostic
builds; ordinary release signing and package identity retain their defaults.

Device fixture sources and SWFs: `.local/device-tests`. Captures and per-scene
logs: `.local/device-results`. The animated fixture moves/rotates 40 groups using
Add, Screen, Subtract, Layer and Normal; it isolates blend-layer churn rather
than simulating game asset loading or proving an OOM fix.

The diagnostic variant configuration was no longer present in the working tree
after the device run. The exact build-only changes have been preserved as
`build/diagnostics/diagnostic-variant.patch`; apply that patch before using
`-PdiagnosticBuild` if the current checkout does not define that property. The
installed APK and saved APK above were built with the diagnostic configuration.

## Renderer sampling ceiling

The Android quality preference now sets a renderer ceiling at startup and when
changed in the settings UI. Movie requests with a higher sample count are
clamped for both main and explicit BitmapData offscreen rendering; requests
below the ceiling remain effective. The movie's Stage.quality value is retained.
Other frontends remain uncapped unless they opt in via `set_quality_limit`.

`gpu-memory` includes requested quality, selected ceiling, effective samples,
live Shape handle count and vertex/index buffer bytes (excluding shape uniforms,
gradient textures, in-flight-only references and driver overhead). `core-memory`
includes GC object count/debt, known movie count, uncompressed SWF payload bytes
and character registrations. Neither is an estimate of the complete native heap.

For diagnostic builds with a host symbol sidecar:

```sh
CARGO_PROFILE_RELEASE_STRIP=none \
CARGO_PROFILE_RELEASE_DEBUG=line-tables-only \
./gradlew -PdiagnosticBuild :app:assembleRelease --console=plain
```

Archive `target/aarch64-linux-android/release/libruffle_android.so` before the next
build. Gradle can strip the packaged library; the archived unstripped binary is
for offline symbolization. Shape, Globals, readback, static and PixelBender
buffer allocations now retain labels in release builds.
