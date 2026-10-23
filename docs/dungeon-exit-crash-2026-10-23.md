# 副本退出时 Loader 卸载重入导致栈溢出

## 手机现场

设备通过无线 ADB 连接；初始端口 34861 掉线后改用 41263。实际运行的是 `rs.ruffle.kabu.diagnostics`，版本 `0.261014`，即之前本地同签名、关闭详细诊断的 Release 测试包。

用户确认复现路径为“进入副本，然后退出副本”。2026-10-23 09:18:51 再次复现，PID 27803 的 `android_main` 线程发生 `SIGSEGV / SEGV_ACCERR`。Android tombstone 明确报告：

> Cause: stack pointer is not in a rw map; likely due to stack overflow.

记录包含 512 层重复堆栈；07:57、07:58 和 09:12 三次历史崩溃有相同特征。这次不能归因为纹理分配 OOM 或系统低内存杀进程。09:18:50 采样 PSS 979952 KiB、RSS 1105156 KiB；退出记录中的 180MB 是进程结束阶段的记录，不能当作游玩峰值。

现场文件位于 `.local/crash-20261023/`，已被 Git 忽略。包含原始 logcat、ApplicationExitInfo、安装 APK、应用保存的诊断文件，以及本次持续采样。应用保存的 `crash-rust.log` 没有与本次对应的时间证据，不能用其中旧的析构 panic 替代本次原生栈溢出诊断。

## 定位与修复

对同版本重建保留符号表后，重复地址初步映射到：

`Loader.unload → LoaderInfoObject::unload → remove_child → removedFromStage → ActionScript 回调 → Loader.unload`

重建库与安装库的 `.text` 布局和长度相同，但字节哈希不完全一致，因此该映射仅作为定位线索，不能声称已完成严格匹配的符号化。后续通过独立回归场景验证代码层面的同一重入问题。

原实现中，`unload` 和 `removedFromStage` 回调运行时，Loader 内容的移除尚未完成。回调再次卸载同一 Loader 会重复触发回调，形成递归。给每个 `LoaderInfoObject` 增加仅在同步卸载期间有效的状态，忽略同一 Loader 尚未结束时的嵌套卸载；正常卸载结束即清除。保持普通卸载的事件顺序和属性重置方式。

修复先在独立核心分支 `codex/fix-loader-unload-reentrancy` 验证，基于 `533ef0884`。用户实际连续三次退出正常，且日志核对无新崩溃后，合入 `0.7.1-A`，合并提交为 `2ea3df167492ecfb7ad4ab6d4f7def7aae88e676`。Android 的六项核心依赖与锁文件同步固定该提交，保持原渲染与缓存优化。

## 验证

- 新增 `avm2/loader_unload_reentrant`：分别在 `unload`、`removedFromStage` 回调中重入，之后复用同一 Loader 正常卸载，并再次卸载空 Loader。回调最多重入三次，避免未修复引擎导致测试进程直接栈溢出。
- 旧核心运行该场景失败，触发移除流程中的 `Option::unwrap()` panic；修复后通过。
- Loader 相关回归：32 通过，2 个 JPEG XR 用例因未启用该特性而忽略；包含 Loader 事件、复用和 `unloadAndStop` 音频测试。
- 新场景的预期输出针对清理和重入不变量，尚未用参考 Flash Player 记录确认。
- 核心单元测试：131 通过。
- Android Release 构建成功；同签名覆盖安装成功，版本 `0.261023-unload-fix1`（261023002），包名保持 `rs.ruffle.kabu.diagnostics`，非 debuggable。APK SHA-256：`a8275f9ef637c9b559c66381c66db739b896c2f2ae68dd43e4b1a1d72317a488`。
- 手机自动测试遇到 ADB 创建目录属于 shell、应用无法读取/写入的问题。回归场景已改为内嵌子 SWF 的 `loadBytes`，输出放应用自身目录，测试主 SWF 直接推送到应用已有目录。该测试准备错误不属于原副本崩溃。
- 手机回归通过：`0.261023-unload-fix1`，PID 15697，三阶段 trace 与预期完全一致。
- 用户按原路径连续进入、退出同一副本三次，均正常。人工复测标记为 09:36:40，游戏从 09:36:47 开始运行，PID 始终为 16357；标记后的日志中原生 fatal signal 和 Rust panic 均为零。验证完成后停止了本次启动的持续采样，手机游戏保持运行。
- 本地修复 APK 继续沿用测试渠道签名；正式 CI 包沿用已配置的固定正式签名。后续 CI APK 的完整构建结果应单独检查，不能以手机测试代替 CI 结果。

- Android 使用远端合并提交和正式锁文件的 arm64 `cargo ndk check --locked --lib` 通过，未依赖测试时的本地路径覆盖。
