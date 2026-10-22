# 渲染优化合并记录（2026-10-22）

## 合并范围

核心开发分支 `codex/local-blend-filter-resolution` 的 `706b2afe6` 已合入 `0.7.1-A`，合并提交 `533ef0884fd87fbd117467bb0ea82d50c4904681`。Android 的全部 Ruffle Git 依赖和 Cargo.lock 固定到该提交，无需本地 path patch。保留当前 Android 系统键盘与登录界面的后续改动。

保留复杂混合局部目标、发光/阴影降采样、简单混合及父遮罩下的保守裁剪；保留前端 MSAA 上限、惰性形状上传、64 帧 Morph 工作集、90 秒闲置资源回收和每池 128 项投影常量 LRU。Alpha/Erase、复杂遮罩及无法证明独立边界的命令仍保守回退。

## 日志开销

普通构建每 5 秒记录基础帧耗时，保留 panic 文件和崩溃时进程内存信息。每秒核心符号遍历、形状弱引用跟踪、纹理尺寸统计/排序、慢帧详细记录和动态资源标签仅在显式 Cargo `diagnostics` feature 开启时启用；该 feature 传递到渲染器 `detailed_diagnostics`。纹理池资源上限、每帧回收和压力提交逻辑不依赖诊断开关。

`./gradlew -PdiagnosticBuild :app:assembleRelease` 自动启用上述 feature，使用独立诊断包名、调试签名和可调试 release；普通 CI `--features jpegxr` 不启用详细统计。不能只替换 APK 包名或使用 CI 预构建的普通原生库来生成诊断版。

普通模式仍保留有限的 BitmapHandle 弱引用跟踪，以保持已有统计 API 的可用性；每 240 帧清除失效弱引用，避免关闭报告后其容器持续增长。

## 合并前复核

- 核心单元测试 131 项通过。
- 渲染器普通模式 13 项通过；诊断模式连同显式 Vulkan 测试 15 项通过（包含画质限制及 LRU 淘汰后活动引用仍有效）。
- 混合模式图像回归 18 项、CacheAsBitmap 图像回归 29 项通过，沿用既定容差及 known-failure 配置。
- Android release 诊断 APK 构建通过；Android JVM 单元测试 13 项通过。
- 真机固定 `MaskedBlendStress`：48/48 个混合层裁剪，临时纹理借出峰值 54.5 MiB，约 23.3–24.0 fps，无 panic。两张截图 SHA-256 均为 `fe8029181750f7e68f937132a7d5be6912322681a816030b4f738bd198325145`，与之前的实机验证版完全一致。

诊断 APK：`build/diagnostics/ruffle-merged-533ef0884-arm64-diagnostic.apk`，SHA-256 `9e1d01a2752844e8b4302407d6a5414e7cb9a50e1e86be3881aa6e23047e8e2c`。固定场景原始数据：`.local/device-results-masked-blend/merge-final-masked-20261022/`。

## 最新实际游玩日志

从 `192.168.1.161:37679` 拉取 `perf.log` 共 1448 个采样窗口（约 24 分钟），无 panic/OOM 文本记录。末尾已停止绘制，不能当作游玩帧率。1403 个有绘制窗口的 FPS 中位数 15.8，render_avg 中位数 55.8 ms；实际人数、任务、战斗及操作并不固定，不能与其他轮次作为受控 A/B。

进程高水位 1775960 KiB（约 1.69 GiB），末尾 RSS 1417580 KiB、Swap 471864 KiB。末尾 known_movies=650，原始 SWF 数据 81.9 MiB。用户本人及其他测试者反馈流畅度改善，但这些记录不能证明长期 OOM 已完全消除，非空符号库仍可能长期持有 SWF。Android 还保留已测试分支的退出期兼容处理：事件接收线程销毁后若仍有本地异步任务被唤醒，保留该任务到进程退出以避免跨线程析构导致 abort；这不是通用的任务取消/释放方案，多次销毁并重建播放器的长期资源释放仍需单独验证。原始日志保存在 `.local/merge-review/device-latest/`。


## 普通 release 与 Android 收尾

关闭详细诊断的 release 构建、release lint、全仓库 ktlintCheck 和仪器测试 APK 编译通过。合并前修复了系统键盘仪器测试同一作用域重复声明 scale 的问题，并按仓库配置格式化 Kotlin 源码；独立提交 `87c94d2`，不改变游戏功能。Rust 格式检查通过。

普通 release 采用独立临时包名 `rs.ruffle.kabu.mergecheck`（Gradle init 脚本覆盖包名，未修改工程默认包名），不可调试，使用本地测试签名。APK 的 .text/.rodata 与优化后的 release 原生库地址、长度、内容一致；gpu-memory、scratch_top、library_graphics 等详细统计字符串已被消除。

真机固定场景运行 10 秒无 panic，5 秒基础日志中约 22.8/24.0 fps，画面正常。新包采用默认 65% 渲染比例、Low 画质和 75% 柔和滤镜设置，与诊断包已有的 100%/Medium/50% 设置不同；这次只验证正常模式绘制及诊断关闭生效，不是帧率 A/B，也不比较截图像素是否相同。原始数据 `.local/device-results-masked-blend/merge-final-normal-20261022/`。

普通模式验证 APK：`build/diagnostics/ruffle-merged-533ef0884-arm64-mergecheck-release.apk`，SHA-256 `fc08a8de5833c49d3e39ec565dc882a5a4c8ca80c222122df27eed2fd66469e2`。安装 local 包曾因已有包签名不同失败，随后旧包采样已隔离在 `invalid-old-local-package-20261022`，未用作验证依据。

原 Android kabu 工作目录的未提交诊断基线先备份至 Git stash（消息 `backup/pre-render-optimization-merge-20261022`），随后快进整合最新 Android 功能、固定依赖与日志开关。历史 APK、设备日志和本地工具保留在原工程忽略目录。
