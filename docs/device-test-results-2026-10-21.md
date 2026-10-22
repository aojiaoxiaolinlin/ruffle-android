# Android device test — 2026-10-21

The diagnostic APK was built, signature-verified and installed on HONOR BVL-AN16 / Adreno 750, Android 16. Core checkout: `codex/local-blend-filter-resolution` (local uncommitted changes). `0.7.1-A` was not merged.

APK: `build/diagnostics/ruffle-trivial-crop-arm64-diagnostic.apk` (arm64, ~35 MiB). SHA-256: `05d8a771850b463125e51ea3655e32fdfbaecabb968191ccb628bc5c0a6136f8`. Package: `rs.ruffle.kabu.diagnostics`; label: 游戏不卡（诊断）. Rust release profile, local debug signing, debuggable Android manifest.

Settings: GLES, 2800×1280 buffer, render scale 100%, Medium quality (2×MSAA), soft filter scale 50%.

| Scene | Outcome | Last-frame crops | Scratch owned |
|---|---|---|---|
| add | Foreground player alive; no panic in perf log | 1/1 | 53.8MiB |
| animated_trivial_stress | Foreground player alive; no panic in perf log | 32/32 | 55.0MiB |
| layer_alpha | Foreground player alive; no panic in perf log | 0/1 | 123.0MiB |
| layer_erase | Foreground player alive; no panic in perf log | 0/1 | 123.0MiB |
| masked_layer_cached_children | Foreground player alive; no panic in perf log | 0/1 | 91.7MiB |
| screen | Foreground player alive; no panic in perf log | 1/1 | 53.8MiB |
| subtract | Foreground player alive; no panic in perf log | 1/1 | 53.8MiB |

All seven scenes launched and produced diagnostics. Six fixed-scene screenshots were compared after converting the phone’s embedded ICC profile to sRGB, extracting the stage and downsampling to reference dimensions. Mean absolute error was about 0.66–1.19 per channel on a 0–255 scale. This is a cross-resolution / cross-quality sanity check, not a pixel-exact regression assertion. The host reference images use Low quality; the phone used Medium.

The custom 40-group moving/rotating fixture then ran for 120.6 seconds with one unchanged PID and no panic/OOM in the captured log. Its eligible 32/32 blend groups were cropped. Scratch owned stayed around 55.0 MiB after warmup; sampled last-frame scratch peak was around 42.1 MiB; final frame rate was about 30 FPS.

| Elapsed | /proc VmRSS (MiB) | Scratch owned |
|---|---|---|
| 30s | 270.4 | 55.0MiB |
| 60s | 273.1 | 55.0MiB |
| 90s | 273.5 | 55.0MiB |
| 120s | 274.2 | 55.0MiB |

This short synthetic workload contains no uploaded game bitmaps; its stability did not predict the normal-gameplay failure recorded below. RSS alone excludes parts of driver accounting; `dumpsys meminfo` is retained separately. No claim is made that normal-gameplay OOM is fixed. The user then logged into the diagnostic package and normal gameplay was monitored for five minutes; results follow below.

Raw screenshots, per-scene perf logs, meminfo and the two-minute samples are in `.local/device-results/`. Test SWFs, AS source and runner scripts are in `.local/device-tests/`.


## Normal gameplay — five-minute observation

After user login, the same process stayed alive for 300 seconds. No panic/OOM
was found in this observation window, but memory grew substantially. This is
not a successful OOM-fix validation.

| Elapsed | /proc RSS (GiB) | Live uploaded estimate (MiB) | Logged FPS window |
|---|---|---|---|
| 0s | 1.18 | 73.9 | 15.5 |
| 30s | 1.40 | 121.8 | 23.7 |
| 60s | 1.38 | 119.7 | 17.4 |
| 90s | 1.64 | 158.9 | 17.2 |
| 120s | 1.74 | 186.6 | 18.9 |
| 150s | 1.73 | 186.6 | 16.3 |
| 180s | 1.83 | 188.2 | 21.4 |
| 210s | 1.92 | 189.9 | 18.8 |
| 240s | 1.96 | 211.0 | 11.7 |
| 270s | 1.97 | 227.5 | 4.7 |
| 300s | 2.11 | 236.8 | 23.4 |

The phone preference file still contained `stage_quality=1` (Medium), but the
runtime renderer reported `samples=4`. `Stage.quality` can call the renderer's
`set_quality` directly (`core/src/avm2/globals/flash/display/stage.rs` and
`core/src/display_object/stage.rs`); the Android preference currently does not
cap subsequent quality changes. The precise initiating setter was not traced.

Scratch idle ownership stayed around 128 MiB while the largest logged
last-frame scratch peak was 910.9 MiB. A slow gameplay sample still had
51/52 blends cropped and 102/102 soft-blur branches reduced. This confirms the
optimizations are active, but they do not bound the total live resource set.

Native Heap increased substantially in `dumpsys meminfo`; Java heap was much
smaller. The observed growth alone does not prove a leak or identify the owner
of all retained native memory. Driver allocations can also appear in native
accounting. No same-workload before/after Android baseline was recorded, so the
device results do not quantify the total savings from the crop patch.

A separate 30-second [Perfetto heapprofd](https://perfetto.dev/docs/data-sources/native-heap-profiler)
session sampled native allocations with a 64 KiB interval and a nonblocking
client. It overran its buffer and disconnected early; its results are partial.
The APK mapping dominated sampled allocation churn, but release symbols were
stripped and this truncated profile cannot establish the cause of total heap
growth. Trace and SQL summaries are retained under `.local/device-results/gameplay/`.
This profiling interval can also perturb timing; do not use those FPS windows
as a controlled performance benchmark.

Recommended next work: enforce the user's selected MSAA limit even when SWF
code changes Stage.quality, and collect symbolized native/asset-lifetime data
for the growing retained allocations. Keep the current work on the development
branch until normal-gameplay memory behavior is understood and validated.


## Post-window OOM reproduction

A final log pull after the five-minute observation caught a Rust panic:

```text
Device::create_buffer -> OutOfMemory
label: ""
```

The panic's `/proc/self/status` snapshot reported VmRSS/VmHWM 2,267,204 KiB
(about 2.16 GiB). Immediately preceding slow-render records estimated uploaded
bitmaps at 254.8 MiB, with the scratch idle pool at 128 MiB and 4×MSAA still
active. The fatal allocation remains unnamed. Shape uniform/vertex/index
buffers still use `create_debug_label!`, which is disabled in ordinary release
builds; internal staging paths are also candidates. The current record cannot
identify which of these allocations failed.

The new crop optimization is functional, but it has **not resolved gameplay
OOM**. The branch should not be treated as validated for integration yet.

Evidence: `.local/device-results/gameplay/crash-rust.log`, `perf-final.log`,
`meminfo-at-crash.txt`, and `screen-at-crash.png`. The diagnostic process was
force-stopped after preserving these files to release its memory. The original
installed game package and its data were retained.

The diagnostic build configuration was observed to have been restored in the
working tree after APK generation. Its build-only diff is preserved at
`build/diagnostics/diagnostic-variant.patch`; the existing configuration was not
overwritten again. The APK linked above is the exact independently installed
and tested artifact.
