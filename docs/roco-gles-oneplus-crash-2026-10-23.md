# roco GLES 崩溃再次出现：OnePlus / Adreno 830（2026-10-23）

## 结论与决定

这次与 [Adreno 750 的崩溃](roco-gles-crash-2026-10-23.md) 高度一致：游戏线程在 Adreno 驱动的字符串复制路径崩溃，源内存摘录直接包含同一个渲染调试标签。按用户此前“再次崩溃后再改”的决定，在 roco 实施 GLES 调试标记与底层资源标签规避；此前暂缓修改的决定由此更新。尚未证明实际游玩中的崩溃已消除。

## 输入资料与现场

- 用户提供目录：`D:\QQ Store\ruffle-diagnostics-1792742125166`。
- 导出时间 15:55:25，崩溃时间 15:55:20，仍为正式包 `rs.ruffle.roco 0.261022.83 (261022832)`。
- 本次设备是 OnePlus PKR110、Android 16、Adreno 830，不是上一次的 HONOR / Adreno 750。
- 使用 wgpu GL 后端；驱动为 OpenGL ES 3.2 V@0800.56.1，版本标识 `6cec7db601`。渲染比例 0.5、低画质、1 sample、柔和滤镜比例 50%。
- 输入包含 device-info.txt、native-crash.log、perf-previous.log、perf.log，没有原始 `.trace.pb`。本次没有连接或修改这台 OnePlus 设备。

## 关键证据与局限

1. 崩溃记录为 `CRASH_NATIVE`、status 11；trace 中可读信号为 `SIGSEGV / SEGV_MAPERR`。
2. `android_main` 线程的栈中依次可见 `strlcpy`、Adreno 驱动函数 `!!!0000!89e7bb26ba44861beafbd451f3e10e!6cec7db601!`、应用原生帧。函数标识的主体与上一台设备的 `89e7bb26ba44861beafbd451f3e10e` 相同，但驱动版本不同。
3. `android_main` 的 x1 和 x9 源内存摘录中直接出现 `(wgpu internal) Pre Pass` 和 `Chunked draw calls (Stencilless)`。后者长 32 字节；x2 字段保留下来的单字节值为 `0x21`（33），与上一轮长度加一复制的现场吻合。
4. trace 开头是 `HeapTaskDaemon` 的等待栈，因为它包含多个线程；不能把第一个线程误认为故障线程。
5. `.83` 仍使用旧版日志保存方式，将 protobuf 强制转为 UTF-8 并截断。可读字符串仍提供证据，但已损坏的数值和 protobuf 长度不可可靠解码；不能把此文件重新编码后当原始 tombstone 使用。这里没有完整恢复 PC、故障地址与匹配符号的全部调用链。
6. perf-previous.log 有 38 个帧率窗口，中位数约 23.75 fps，范围 16.5–24.4 fps；纹理池在多个窗口到达 128 MiB。这不是总 GPU 内存，也没有伴随本次 OOM 分配失败的直接证据。perf.log 是后续 renderer 初始化及短时运行记录，不能当作崩溃前的完整采样。

以上证据支持同类 GLES 调试字符串兼容性问题，并已满足此前决定的修改条件；仍需修复版本实际游玩验证。

## roco 的规避实现

- 在 Android renderer 初始化中显式设置 `GlDebugFns::Disabled`，关闭 GLES debug group / marker 路径，包括 HAL 内部生成的分组。
- GLES 实例同时设置 `InstanceFlags::DISCARD_HAL_LABELS`，让资源标签不再下传到 HAL；仅删除上层某个标签不能覆盖内部标记。
- 保留原先 Ruffle 的默认实例参数、间接调用验证兼容设置、设备/队列申请逻辑、HighPerformance 选择及 Vulkan 失败后回退 GLES 的行为。Vulkan 不设置丢弃标签标志。
- 画质、渲染比例、缓存策略和应用自身的性能/崩溃日志不变。正常日志会记录 `GLES debug functions disabled (Adreno crash workaround)`，便于核对新版是否走了规避路径。
- 改动位于 Android 工程，不依赖修改本机 Cargo 缓存或远端核心分支；直接声明已锁定的 futures 依赖，用于保持原有同步初始化方式。

验证：ARM64 `cargo ndk check --locked --lib --offline`、Clippy `-D warnings`（普通配置和 CI 使用的 `jpegxr` 特性）通过。JPEG XR 的本机检查显式使用 NDK 自带 libclang。尚未完成修复版 APK 在 OnePlus 上的运行验证，也未把旧包 `.83` 的再次崩溃计作新修复的失败。

## 证据保存

四份原文件已复制到当前排查工作区 `.local/roco-ci-bug-oneplus-20261023/`，附 `manifest.json` 和只含可读片段的摘录；原始目录未修改。native-crash.log 为 80,729 字节，SHA-256：`d5994682e5d8c85776a6e658f79d0cce22bee181cae4335ed0258765cb1a7962`。

该原始目录仍被 Git 忽略，换环境需单独备份。此报告只保留必要结论和证据摘要，不公开完整设备日志。
