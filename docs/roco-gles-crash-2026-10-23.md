# roco CI 包 GLES 崩溃记录（2026-10-23）

## 当前决定

更新：同日 15:55:20，另一台 OnePlus / Adreno 830 设备在旧版 `.83` 上出现同类崩溃，内存摘录直接包含同一调试标签。已按此前约定在 roco 实施规避，详见 [再次崩溃记录](roco-gles-oneplus-crash-2026-10-23.md)。下段保留首次分析时的决定；修复后实际游玩验证仍待完成。

用户决定先记录，继续正常游玩，暂不禁用 GPU 调试标签。此次场景来自随意游玩，没有稳定的复现步骤。若再次崩溃，先保存新日志并核对调用栈；若仍指向同类 GLES 调试字符串问题，再实施下述规避方案。后续以正常游玩的运行时长和崩溃记录观察效果，不要求还原完全相同的场景；短期未复现不代表已经修复。

## 现场与证据

- 正式包：`rs.ruffle.roco`，版本 `0.261022.83`，versionCode `261022832`，arm64-v8a。用户说明来自第一次推送后的 CI；未独立核对构建对应的 Git SHA。
- 设备：HONOR BVL-AN16，Android 16，Adreno 750。无线调试连接当时为 `192.168.1.161:45101`。
- 使用 wgpu GL 后端，驱动 OpenGL ES 3.2 V@0762.46，渲染比例 0.65、低画质、1 sample。
- 15:16:01.361，PID 29815、TID 31709（`android_main`），发生 `SIGSEGV / SEGV_ACCERR`。系统退出记录确认为 native crash，进程存活约 204 秒。
- 栈顶部：`libc.so strlcpy+16` → `libGLESv2_adreno.so 0x2fbf6c` → 应用原生库 `0x13d83b8` 等。
- 驱动反汇编显示长度加 1、分配目标缓冲区后调用 `strlcpy`。现场源地址 `0xb4000079cfbd0fe0`、长度参数 `0x21`、故障地址 `0xb4000079cfbd1000`，故障恰在源地址后第 32 字节。
- 核心源码中的调试标签 `Chunked draw calls (Stencilless)` 长 32 字节，其第 16–23 字节 `ls (Sten` 与寄存器 x14 的小端字节一致。

最可能是驱动处理带显式长度、未额外补 NUL 的调试字符串时越界读取。该判断有上述证据支持，但正式 APK 已剥离内部符号，尚未用完全匹配的 CI 符号还原全部帧，也未完成复现验证。没有本次 OOM 分配失败或之前 Loader 卸载递归栈的证据；退出 RSS 约 192 MiB 是系统采样值，不能排除所有内存问题。

## 待触发的规避方案

在 Android GLES 的 wgpu BackendOptions 中设置 `gl.debug_fns = GlDebugFns::Disabled`，关闭 GPU 调试名称及标记调用。该选项降低 GPU 抓帧的可读性，不改变游戏画质、分辨率、特效或缓存策略，也不关闭应用性能日志、错误日志及崩溃记录。尚未实施或验证此方案。

原始日志、安装 APK、驱动库及详细分析保存在本工作区被 Git 忽略的 `.local/roco-ci-bug-20261023/`。其中旧版 `native-crash.log` 已把 protobuf 转成 UTF-8 文本并截断，不能作为完整二进制 tombstone 使用；本次主要依据系统原始 crash/logcat。采集未替换安装包或清理应用数据。
