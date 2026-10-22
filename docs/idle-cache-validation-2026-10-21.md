# 2026-10-21 长时间游玩资源排查

分支：Android `kabu`，Ruffle 核心 `codex/local-blend-filter-resolution`。核心改动尚未合并至 `0.7.1-A`。

## 观察

- 上轮资源缓存 APK 的正常游玩监测从 0 至 300 秒：`known_movies` 179→736，RSS 约 1.09→1.43 GiB，Swap 约 20→533 MiB。更晚的日志达到 `known_movies=1590`、图形缓存约 297 MiB、RSS 约 2.34 GiB。此前 5 分钟无 OOM 不能说明长期稳定。
- 另一次退出记录有 `async-task` 的 `local task dropped by a thread that didn't spawn it`，随后析构期间 abort。这是异步本地任务在线程错误处被释放的崩溃，与 OOM 分开处理。
- `MovieLibraries` 是弱键映射，但 `MovieLibrary.swf` 原来强持有自己的键；空库也无法随外部 SWF 引用释放。另有图形、动画、位图等库值可持有 SWF，仍需长期游玩实测并继续分析。

## 本轮改动

- Android 渲染后每 5 秒扫描一次已定义图形；若某图形 90 秒没有渲染，清除可重建的缩放网格、MorphShape 插值帧和相应渐变网格引用。当前帧命令继续持有引用直到提交结束，活跃图形的缓存保留。
- `MovieLibrary.swf` 改为弱引用，并在实例化位图时升级；删除空库对 SWF 的自持。核心生命周期测试覆盖“外部引用释放后弱键消失”。
- Android 事件通道关闭后若 Tokio 工作线程仍发送本地 `Runnable`，保留它给进程退出清理，避免在错误线程执行析构导致 abort。

## 已完成验证

- Ruffle 核心 130 项单元测试通过。
- 使用独立 SWF，在 2 秒后将图形 A 移出舞台，同时持续渲染图形 B。手机端 5 秒时 `graphic_mesh_refs=2`、GPU `shapes=2`；110 秒时分别降为 1 和 1，活跃图形 B 未被回收；测试进程无 panic。原始采集在 `.local/device-results-idle-cache/optimized/`。
- 第一版闲置缓存 APK 已在 BVL-AN16 实测。加入电影库弱引用后的第二版已构建并覆盖安装，SHA-256：`08eaa5c10539b3d75c4ed3e3b01b66f758dabafad9235595410d5c065b2cfd46`。仍需实际游玩验证。

这些计数不是总 GPU 内存；SWF payload 字节数也不含解码纹理、VM 对象等。固定场景证实闲置图形回收有效，不能据此断言游戏中 OOM 已解决。

## 新版连续游玩 20 分钟

用户进入游戏后，PID 12391 连续在 PlayerActivity 运行 20 分钟；两段各 10 分钟的监测均未发现 panic，进程在最后采样时仍存活。末尾 `known_movies=1338`、角色定义 56,455、原始 SWF payload 93.8 MiB、位图填充缓存引用 1,952，GPU 统计的已上传位图 3,134。图形网格引用末尾 1,070、顶点/索引缓冲约 31.5 MiB，期间明显波动并多次回落，表明闲置网格释放在游戏中运行。

末尾 RSS 约 2,215 MiB、Swap 约 44 MiB；历史 RSS 高水位约 2,773 MiB，随后曾回落至约 1,680 MiB。高水位远高于末尾值，不能把回落视为长期 OOM 已解决。上传位图估算末尾 256.6 MiB，电影库与位图数量仍持续增加；图形网格释放只覆盖这部分资源的一小部分。

采样记录：`.local/device-results-idle-cache/gameplay/monitor.json` 和 `continued/monitor.json`，每 30 秒的 perf 与 meminfo 快照在对应目录。用户明确说明每次是不同操作的日常游玩；这些曲线只用于识别资源增长、峰值和崩溃，不能与其他游玩轮次计算受控 A/B 节省率。

## 闲置位图填充回收

静态 SWF 的 `BitmapCharacter` 原先通过 OnceCell 永久保存首次上传的纹理。WGPU 网格在提交时只保存 bind group，不计入 `BitmapHandle` 的 Arc 引用数；先让网格绘制项保留对应 handle，再允许 Android 的 90 秒扫描清除仅由角色库持有的闲置填充纹理。这样仍在网格中使用的纹理不会被误判为无人持有，新的网格需要时也可从压缩源重新解码上传。其他前端默认不启用闲置扫描。

核心 131 项单元测试、WGPU 编译检查和 `acid-bitmap-fill` 2 项图像回归均通过。arm64 诊断 APK SHA-256：`3f7397ad7c660dcea871173802734b3bc15c8bf5499151c277b14af30532dad0`，文件 `build/diagnostics/ruffle-idle-bitmap-arm64-diagnostic.apk`，匹配符号 `build/diagnostics/idle-bitmap-symbols/libruffle_android.so`。

同一手机与同一 SWF：前 2 秒绘制带 512×512 位图填充的图形，移除后继续播放；110 秒时旧版和新版图形网格均从 7 降到 1，旧版 `bitmap_fill_refs=1`、`uploaded_count=1`，新版均为 0。四张 5 秒及 110 秒截图的 PNG SHA-256 相同。原始对照数据在 `.local/device-results-idle-cache/bitmap-{baseline,optimized}/`。这个场景只含 1 MiB 纹理，RSS 波动不足以作为节省量；真实游戏的收益仍需继续验证。

## 位图优化版日常游玩与活跃资源回归

用户在位图优化版正常游玩 10 分钟，PID 32085 全程在 PlayerActivity，未见 panic 或进程退出。起点到终点 `known_movies` 10→1085、原始 SWF payload 23.5→83.4 MiB；末尾 `bitmap_fill_refs=309`、GPU `uploaded_count=1389`。中途的位图缓存引用和 GPU 已上传数多次回落，例如 420→600 秒分别由 814→309、1750→1389，说明闲置释放在实际游玩中执行，但无法把全部下降归因于这一项改动。RSS 最高约 1.45 GiB，末尾约 1.05 GiB；Swap 末尾约 0.91 GiB。后段 540–600 秒渲染速度约 7–9 fps，单次渲染平均约 86–106 ms；此时脚本 tick 平均约 29–33 ms。用户说明多次会去在线人数较多的区域，点击人物移动时掉帧明显；日志没有操作/场景标记，不能把上述采样直接对应到具体点击。

活跃位图固定 SWF 在 110 秒时仍为 `bitmap_fill_refs=1`、`uploaded_count=1`，其 5 秒和 110 秒截图 SHA-256 完全相同，进程无 panic。由此验证 90 秒扫描没有误删仍在网格中使用的 512×512 位图纹理。原始数据在 `.local/device-results-idle-cache/bitmap-gameplay/monitor.json` 和 `bitmap-active/summary.json`。

这两轮日常游玩经过的场景和操作均不相同，不能拿 RSS、帧率或资源数量差值做受控 A/B 优化收益。长时间 OOM 仍未排除：电影库、角色定义和 GPU 上传数量随内容加载增长，且 Swap 较高；合并至 `0.7.1-A` 前还需针对高人数场景和更长游玩验证。

## 仍需解决的 SWF 持有链

源码确认 `MovieLibraries` 虽用 `Weak<SwfMovie>` 作键，但其值中的 `Character::MovieClip` 指向 `MovieClipShared`，后者的 `movie: Arc<SwfMovie>` 与 `swf_data: SwfSlice` 强持有对应电影。非空电影库仍可形成“弱键映射的值 → 电影 → 键存活”的闭环，因而本轮 `MovieLibrary.swf` 改弱引用只消除了空库的自持，不能推断实际游戏的 `known_movies` 会回落。需要把定义、实例和脚本对象的可达性一并考虑；按时间直接删除电影库会破坏仍在使用的符号。

## 同轮高人数场景与仓库操作

用户在同一游戏进程 PID 14117 中两次进入高人数场景，并且两次都打开仓库；第一次未发送进入标记，第二次“已到”消息也晚于实际进入，因此阶段边界只有近似值。5 秒采样的第一段重负载约 0–60 秒，帧率中位数 7.6 fps、平均渲染耗时中位数 104.4 ms；第二段约 90–135 秒，分别为 12.3 fps、68.1 ms。第一次与第二次之间短暂回到约 24 fps，但没有精确场景标记。用户报告 16:55:21 离开；约 155–370 秒的离场阶段帧率中位数 23.8 fps、平均渲染耗时中位数 21.4 ms，期间有一次 5.6 fps 的孤立采样，其 tick 最大耗时约 3 秒，不代表持续渲染负载。两段重负载包含仓库操作，不能把差异单独归因于在线人数。

重负载慢帧日志共 101 条，单帧临时纹理新建量中位数约 175.9 MiB、26 个纹理，且每慢帧通常有 1 次纹理压力提交；约 19 个可延后图层、12 个回退图层。Android 临时纹理空闲池上限为 128 MiB，这些数字说明高负载画面仍反复分配超出空闲池容量的临时纹理；不能直接提高池上限，因为此前 192/256 MiB 配置在设备上触发过 GPU OOM。离场后 `bitmap_fill_refs` 从约 511 逐渐降至 57，GPU `uploaded_count` 从约 1070 降至约 640，电影库 `known_movies` 仍约 373，没有同样释放。RSS、Swap 随系统回收波动，不宜作为短时场景收益量。原始数据和用户标记在 `.local/device-results-idle-cache/busy-scene/`。

下一步优先针对高负载画面的临时纹理创建追踪尺寸、用途及可裁剪边界，再选择能减少分配而不提高 GPU 常驻内存的改动；SWF 定义持有链仍需单独解决。此轮只验证场景相关的掉帧与离场恢复，不证明 OOM 已修复。
