# 投影常量缓存逐项淘汰验证

以用户选定的 2026-10-22 遮罩内简单混合裁剪版为基线，保留全部已有裁剪、滤镜降采样、画质上限和资源回收改动。核心分支 `codex/local-blend-filter-resolution`，Android `kabu`；未合并 `0.7.1-A`。

## 问题与改动

先前 `TexturePool::globals_cache` 是按渲染目标宽高缓存投影矩阵的映射；超过 128 项时在帧末整批清空。上一轮原生 CPU 采样中 `Globals::new` 包含子调用约占 4.8%，因此选择优先减少常用尺寸的缓冲区/绑定组重建。

改为现有 `lru` 依赖提供的缓存，每池始终最多保留 128 项，淘汰最久未用项；保留 FNV 哈希。正在使用的 `CommandTarget` 继续持有 Arc，缓存淘汰不使其失效。主池与离屏池独立计数，故总条目数最多 256，并非两个池合计 128。纹理空闲池仍为 128 MiB，画质和滤镜分辨率没有调整。

增加累计命中、未命中、淘汰计数及当前条目数，随现有每秒 GPU 内存快照记录。

## 验证

- WGPU 普通单元测试 13 项通过；显式运行需要 Vulkan 的新 GPU 测试通过，验证常用尺寸跨帧复用、128 项容量和被淘汰资源仍由活动引用保留。
- 混合模式 18 项、CacheAsBitmap 29 项图像回归通过；原测试的既定容差和 known-failure 配置保留。
- Android arm64 release 诊断构建通过并安装到 BVL-AN16，ADB 端口 44829。

固定 SWF `ProjectionCacheStress`：每帧向 120 个固定尺寸 BitmapData 执行 draw，再向一个每帧改变尺寸的 BitmapData 执行 draw，随后释放变化目标。主池占 1 个投影条目，离屏池在 128 项上限保持稳定。2–10 秒间命中增加 20328、未命中增加 168，增量命中率约 99.18%；尺寸持续增加时常用集合仍被复用。两版 FPS 均约 24，旧版与新版平均 render 耗时范围接近，这个用例不支持宣称可见帧率提升。

两版各两张截图 PNG 的 SHA-256 均为 `64c45c2e592abb88d0b0172c6b5e972769f9e125591251067e0cf5046239ff81`，无 panic；纹理借出峰值都约 41.1 MiB，10 秒时纹理池持有约 52.5–52.6 MiB。原始数据 `.local/device-results-masked-blend/projection-{before,after}/`，用例源码与运行脚本在 `.local/device-tests/`。

## 实际游玩

09:27:11 开始，PID 21044 连续采集约 280 秒。用户说明初始大厅比前一轮人数更多，随后去低人数位置、切场景、进行一场简单对战并离场；不同轮次不是受控 A/B。阶段以消息近似，实际切换可早于消息。

| 阶段 | 采样秒数 | FPS 中位数 | render_avg ms 中位数 |
| --- | --- | --- | --- |
| 大厅 | 25–75 | 15.9 | 55.0 |
| 低人数位置 | 95–160 | 23.9 | 23.0 |
| 简单对战附近 | 185–210 | 24.2 | 24.8 |
| 战后及离场 | 215–280 | 23.9 | 27.3 |

全程增量缓存命中率约 75.2%，两池条目数均不超过 128，进程未退出、无 panic；用户反馈这一轮体感流畅。固定用例 99.18% 不可当作真实游戏的通用命中率。真实场景仍有数百次/秒投影重建，缓存策略改进不等于该开销已经消除；目前未量化相对基线的实际游戏 CPU 或帧率收益。

末尾 RSS 约 1302 MiB、Swap 约 14 MiB，`known_movies=244`、原始 SWF 36.3 MiB、图形缓存引用 584、位图填充引用 116。此轮时间不足以证明长期 OOM 已解决，SWF 定义长期持有仍是独立问题。

原始采样 `.local/device-results-idle-cache/projection-lru-gameplay-20261022/`。

## APK 与符号

- APK：`build/diagnostics/ruffle-projection-lru-arm64-diagnostic.apk`
- SHA-256：`ed8bc148d96951328e0f4fef7d5c6e96c347c49285278105d8ed16f7dfb8079c`
- 未剥离符号：`build/diagnostics/projection-lru-symbols/libruffle_android.so`
- 保留的安装版原生库：同目录 `installed-stripped.so`
- `.text` 地址 `0x5e3b00`、长度 15617400、SHA-256 `f8be33fcb435076e4661cca766adc84f35f9f91aa07b473d735770cd951cb07f`；`.text` 与 `.rodata` 地址、长度及内容均验证与安装版相同，详情同目录 `matching-native-symbols.json`。
