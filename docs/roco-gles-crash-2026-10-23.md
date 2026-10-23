# roco CI 包 GLES 崩溃记录（2026-10-23）

## 当前决定

卡布的新日志再次出现同类 GLES 调试字符串崩溃后，用户已明确要求关闭 GPU 调试标签。核心已实施 Android GLES 规避，kabu、roco 同步固定该核心版本。后续以正常游玩的运行时长和崩溃记录观察效果；尚无修复后真机验证，短期未复现也不代表已经根治。

## 现场与证据

- 正式包：`rs.ruffle.roco`，版本 `0.261022.83`，versionCode `261022832`，arm64-v8a。用户说明来自第一次推送后的 CI；未独立核对构建对应的 Git SHA。
- 设备：HONOR BVL-AN16，Android 16，Adreno 750。无线调试连接当时为 `192.168.1.161:45101`。
- 使用 wgpu GL 后端，驱动 OpenGL ES 3.2 V@0762.46，渲染比例 0.65、低画质、1 sample。
- 15:16:01.361，PID 29815、TID 31709（`android_main`），发生 `SIGSEGV / SEGV_ACCERR`。系统退出记录确认为 native crash，进程存活约 204 秒。
- 栈顶部：`libc.so strlcpy+16` → `libGLESv2_adreno.so 0x2fbf6c` → 应用原生库 `0x13d83b8` 等。
- 驱动反汇编显示长度加 1、分配目标缓冲区后调用 `strlcpy`。现场源地址 `0xb4000079cfbd0fe0`、长度参数 `0x21`、故障地址 `0xb4000079cfbd1000`，故障恰在源地址后第 32 字节。
- 核心源码中的调试标签 `Chunked draw calls (Stencilless)` 长 32 字节，其第 16–23 字节 `ls (Sten` 与寄存器 x14 的小端字节一致。

最可能是驱动处理带显式长度、未额外补 NUL 的调试字符串时越界读取。该判断有上述证据支持，但正式 APK 已剥离内部符号，尚未用完全匹配的 CI 符号还原全部帧，也未完成复现验证。没有本次 OOM 分配失败或之前 Loader 卸载递归栈的证据；退出 RSS 约 192 MiB 是系统采样值，不能排除所有内存问题。

## 已实施的规避方案

核心提交 `0c0fa8be64903b93cceb9633fb0424c6f20db74b` 在 `create_wgpu_instance` 内以 `target_os = "android"` 限定设置 `gl.debug_fns = GlDebugFns::Disabled`，关闭 GPU 调试名称及标记调用，覆盖首次创建和重新创建实例的路径。该选项降低 GPU 抓帧的可读性，不改变游戏画质、分辨率、特效或缓存策略，也不关闭应用性能日志、错误日志及崩溃记录。Vulkan 的配置及其他平台保持原样。

核心的 ARM64 Android `cargo ndk check -p ruffle_render_wgpu --locked --offline`、渲染器格式检查和 `git diff --check` 已通过。kabu、roco 使用正式远端依赖与锁文件的 ARM64 `cargo ndk check --locked --lib` 均通过。编译通过不能替代真机游玩验证。

## 卡布同类崩溃（20:18:57）

- 用户提供诊断目录 `D:\AppData\qq\Downloads\ruffle-diagnostics-1792757940492`；原始 tombstone 为 `native-crash.trace.pb`，347463 字节，使用 protoc 解码并按 AOSP tombstone schema 解释。
- 正式包 `rs.ruffle.kabu`，版本 `0.261022.79`，versionCode `261022792`；OnePlus PJE110，Android 16，Adreno 740，GLES 驱动 `V@0676.76.1`，渲染比例 0.65，低画质，1 sample。
- 时间 `2026-10-23 20:18:57.826599296+0800`，PID 5614，TID 6018（`android_main`），`SIGSEGV / SEGV_MAPERR`；tombstone 记录进程存活约 11 秒。
- 栈顶部 `libc.so strlcpy+20` → `libGLESv2_adreno.so 0x1a2a8c` → 应用原生代码 `0x13e8c38`。应用内部帧已剥离符号，尚未完成匹配符号还原。
- 源地址寄存器 x1 与故障地址相差 32 字节，长度寄存器 x2 为 33；x1 附近的内存恰以 32 字节标签 `Chunked draw calls (Stencilless)` 结尾，与 roco 的证据高度一致，支持同类驱动越界读取判断。
- 两份 perf 快照的纹理池约 37.5 / 40.2 MiB；没有本次 OOM 或 Loader 递归栈的直接证据。纹理池不代表 GPU 总占用，退出记录 RSS/PSS 为零也不能作为游玩内存测量。

原始日志、安装 APK、驱动库及详细分析保存在本工作区被 Git 忽略的 `.local/roco-ci-bug-20261023/`。其中旧版 `native-crash.log` 已把 protobuf 转成 UTF-8 文本并截断，不能作为完整二进制 tombstone 使用；本次主要依据系统原始 crash/logcat。采集未替换安装包或清理应用数据。
