# Android MSAA ceiling validation — 2026-10-21

The Android quality setting now bounds the renderer's sample count. SWF code
can keep its Stage.quality value and request a lower quality, but requests above
the selected ceiling are clamped. The bound applies to main-frame rendering and
explicit BitmapData offscreen draws. Other frontends have no ceiling by default.

The renderer keeps the last requested quality separately so changing/removing
the ceiling can restore it. Repeating a capped request does not recreate an
unchanged surface. Startup and Android settings events both update the ceiling.

Validation completed:

- Android arm64 compilation check passed.
- 13 renderer tests passed, including the explicitly enabled Vulkan test covering
  high movie requests, resize, changing the ceiling, and removing it.
- 19 existing blend/mask image regression tests passed.
- Updated diagnostic APK built, signature-verified and installed with data kept.
- Four on-device scenes completed without panic: Add, Layer+Alpha, Layer+Erase,
  and a custom animation requesting `16x16` on every frame.
- On that animation, core Stage.quality and renderer requested quality both
  reported `16x16`, the ceiling was `Some(Medium)`, and effective sampling stayed
  at 2. The 32 eligible blend groups were cropped; scratch owned was 55.0 MiB.
- Three fixed-scene screenshots had per-channel mean absolute errors around
  0.67–1.19/255 after ICC conversion and stage downsampling, consistent with the
  previous Medium-quality captures.

Artifact: `build/diagnostics/ruffle-quality-cap-arm64-diagnostic.apk`
SHA-256: `c63283e6e0b3bf3330e2e42cdeec28e43f3f64b679d726c5450148f053c5b261`
Package: `rs.ruffle.kabu.diagnostics` (游戏不卡（诊断）).
Host symbols: `build/diagnostics/quality-cap-symbols/libruffle_android.so`;
`.debug_line` and `.symtab` sections verified. The packaged library may be stripped
by Gradle, so preserve the matching host binary for offline address resolution.

Additional diagnostics:

- Release buffer labels cover Shape uniforms/vertices/indices, global uniforms,
  readback, static buffers and PixelBender, including allocation sizes where
  available. wgpu-internal allocation paths may remain unnamed.
- Live Shape weak handles and vertex/index buffer byte estimates are sampled;
  tracking does not retain shapes. Uniform/gradient/in-flight-only allocations
  are excluded from that estimate.
- GC object count/debt, known movie count, uncompressed SWF payload bytes and
  registered character count are recorded in `core-memory`. These are ownership
  counters, not a complete native heap measurement.

The previous build's normal-gameplay OOM is documented separately in
`device-test-results-2026-10-21.md`. This ceiling fix alone is not evidence that
the complete OOM problem is resolved. Normal-gameplay validation follows user
login into the updated diagnostic app. No merge into `0.7.1-A` has been made.

Code inspection also found upstream commit `e22a464a5` (Store SwfMovie behind a
Gc instead of an Arc). Its message explicitly describes this as a prerequisite
for eventually fixing a movie memory leak and notes that the weak-map version
already leaked. It is not a complete fix to cherry-pick by itself. The new
movie/GC counters are intended to quantify this ownership path before attempting
any lifetime changes.

## Normal gameplay after login

The updated APK stayed in PlayerActivity with PID 20284 throughout a 300-second
normal-play observation (11 samples, 30-second spacing). No Rust panic or OOM was
found in the observation logs or the final follow-up pull. All samples retained
`requested_quality=high quality_limit=Some(Medium) samples=2`.

| Elapsed (s) | RSS (MiB) | Swap (MiB) | Known SWFs | Shape vertex/index (MiB) | Uploaded bitmap estimate (MiB) |
| --- | --- | --- | --- | --- | --- |
| 0 | 889.2 | 20.4 | 119 | 46.0 | 41.7 |
| 30 | 1162.9 | 14.2 | 254 | 178.3 | 80.7 |
| 60 | 974.3 | 454.9 | 365 | 266.6 | 115.3 |
| 90 | 876.8 | 594.0 | 520 | 308.7 | 124.8 |
| 120 | 1354.4 | 400.8 | 621 | 395.3 | 156.9 |
| 150 | 1475.0 | 348.5 | 623 | 396.2 | 155.2 |
| 180 | 1401.0 | 352.3 | 623 | 396.3 | 150.9 |
| 210 | 1415.1 | 351.3 | 623 | 396.3 | 150.9 |
| 240 | 1468.9 | 350.9 | 626 | 396.7 | 152.8 |
| 270 | 1498.5 | 360.2 | 626 | 396.7 | 152.8 |
| 300 | 1416.8 | 480.5 | 667 | 423.9 | 172.2 |

RSS high-water mark at the final sample was 1569.3 MiB. SWF payload bytes grew
27.9→53.6 MiB, registered characters 6,635→25,856, and live shapes
4,385→18,463. GC counts fluctuated rather than increasing monotonically.
The scratch idle pool stayed at 128 MiB; last-frame scratch peaks can exceed it
(292.9 MiB at the final sample). Counts stabilized temporarily around 120–270 s,
then loading activity increased them again. This is evidence of substantial
retained resources, not proof that any particular retention is unintended.

A 20-second native heap trace was collected with a 2 MiB sampling interval,
32 MiB shared buffer and 5-second dumps. It contains 1,421 allocation records;
no nonzero error-severity stats, missing packets or non-finalized profiles were
reported. The previous trace's buffer-overrun failure did not recur.
Offline symbolization with this APK's matching host library resolves app PCs.
Leading positive net sampled stacks include MovieLibrary display-object
instantiation (MovieClip/AVM2 button construction), added-event dispatch, and
smaller blur texture/wgpu queue paths. These samples cover allocations made
inside the profiling window, not all pre-existing allocations, and the coarse
interval is unsuitable for precise per-object accounting. Positive net sampled
bytes are not proof of a leak; allocation churn is not retained heap size.

Raw captures and symbolized stacks are in
`.local/device-results-quality-cap/gameplay/`, including `monitor.json`,
`native-heap.perfetto-trace` and `native-heap-live-stacks.json`.
The profiler window may affect frame timing. This run is not a controlled A/B
performance comparison with the earlier gameplay run.

Conclusion: the MSAA ceiling is verified in the real game, but the remaining
movie/character/geometry ownership and cache retention need investigation.
Five minutes without OOM is insufficient to claim the OOM issue fixed or to
approve merging into `0.7.1-A`. No merge was performed. The running game was
left open and user data was preserved.
