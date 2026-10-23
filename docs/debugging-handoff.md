# 排查资料索引与环境迁移

## 先读哪些文件

| 问题 | 记录 |
| --- | --- |
| Android OOM 排查方法、工具与构建环境 | [android-oom-debugging.md](android-oom-debugging.md) |
| 渲染优化合并、日志开关、当前依赖基线 | [render-optimization-merge-2026-10-22.md](render-optimization-merge-2026-10-22.md) |
| 资源持有、电影库及堆分析 | [resource-retention-validation-2026-10-21.md](resource-retention-validation-2026-10-21.md) |
| 闲置网格/位图缓存释放、实际游玩观察 | [idle-cache-validation-2026-10-21.md](idle-cache-validation-2026-10-21.md) |
| 遮罩与混合优化、慢帧、用户选择的性能基线 | [masked-blend-validation-2026-10-22.md](masked-blend-validation-2026-10-22.md) |
| 投影缓存、LRU 验证与 APK/符号信息 | [projection-cache-validation-2026-10-22.md](projection-cache-validation-2026-10-22.md) |
| 画质与 MSAA 上限验证 | [msaa-cap-validation-2026-10-21.md](msaa-cap-validation-2026-10-21.md) |
| 较早的真机测试与性能采样 | [device-test-results-2026-10-21.md](device-test-results-2026-10-21.md) |
| 副本退出时 Loader 卸载重入崩溃及已验证修复 | [dungeon-exit-crash-2026-10-23.md](dungeon-exit-crash-2026-10-23.md) |
| roco、kabu 的 GLES 崩溃及禁用 GPU 调试标签规避 | [roco-gles-crash-2026-10-23.md](roco-gles-crash-2026-10-23.md) |
| kabu auth_string 持久化、复用与重登触发条件 | [kabu-session-persistence.md](kabu-session-persistence.md) |
| 固定发布签名、版本号及覆盖更新 | [android-release-updates.md](android-release-updates.md) |
| 公共 Android 能力回移到 base 的范围 | [shared-android-backport-2026-10-22.md](shared-android-backport-2026-10-22.md) |

文档中的验证结论只适用于对应版本和场景；文档日期、后续更正与用户决定比早期临时结论优先。以实际分支源码和 Cargo.lock 为当前实现依据。

## 最近状态（2026-10-23）

- base、kabu、roco 已按用户要求删除浮动菜单和系统键盘两项界面集成测试，保留输入逻辑测试；提交分别为 `4f0e433`、`2f32f25`、`6b5cf37`，均已推送。
- kabu `8c410ac`、roco `1740980` 已推送：完整保存原生 tombstone，ANR trace 保持文本可读。kabu 另有格式提交 `e3eb64e`。
- roco CI 安装包 `0.261022.83` 于 15:16:01 崩溃；kabu `0.261022.79` 于 20:18:57 再次出现同类 Adreno GLES 调试字符串越界证据。用户已要求关闭 GPU 调试标签，核心 `0c0fa8be64903b93cceb9633fb0424c6f20db74b` 实施 Android GLES 规避，kabu、roco 同步固定依赖。ARM64 核心编译检查通过，尚待修复后真机游玩验证，详见上述崩溃记录。
- 正式 roco 与卡布包使用同一签名证书，包名不同，数据与更新独立。

本索引和 roco GLES 崩溃记录随本次文档提交纳入 roco、kabu 的版本控制。后续新增记录仍应核对 Git 状态与推送结果，不能只因文件位于 docs 就认为已备份。

kabu 后续更新：已加入 auth_string 加密持久化与“继续游戏”，明确重登时清除票据，详情见上述会话记录。JVM 16 项、独立包名 Android 存储测试 3 项及 ARM64 普通/jpegxr Clippy 通过；没有使用真实账号测试服务器过期流程。

## roco 与 kabu 的资料盘点（2026-10-23）

两个分支原有的 11 份 docs 报告均已纳入 Git，内容一致。kabu 工作区 `/home/linlin/Code/Rust/ruffle-android-kabu/` 没有发现未跟踪的新报告，也没有 `.local/` 或 `build/diagnostics/`；这不代表历史采集已迁入 Git。

本次实际核对结果如下；路径都是原机器的本地位置，并非可下载的远端地址。

| 内容 | 核对结果 |
| --- | --- |
| 历史设备测试及采集 | 原工程 `/home/linlin/Code/Rust/ruffle-android/.local/` 中存在 `device-tests/`、`device-results/`、`device-results-idle-cache/`、`device-results-masked-blend/`、`device-results-quality-cap/`、`device-results-before-quality-cap/`、`device-results-resource-cache/` |
| 历史诊断 APK 与符号目录 | 原工程 `build/diagnostics/` 存在；具体报告引用的每个文件仍需逐项核对 |
| 最新 roco 崩溃原始资料 | 当前 worktree `/home/linlin/.codex/worktrees/1a03/ruffle-android/.local/roco-ci-bug-20261023/` 存在 |
| 旧副本崩溃原始目录 `.local/crash-20261023/` | 在上述原工程、kabu 工作区及当前 worktree 均未找到；只确认 Git 报告仍在，不能声称原始证据已保留 |
| 旧合并游玩采集 `.local/merge-review/` | 在上述三个工作区均未找到；其他备份位置未核对 |

本次提交只同步分析文档与资料索引，没有上传原始日志、APK、符号库或签名资料，也没有生成这些本地资料的异地备份。

## 重新克隆不会带走的资料

`.gitignore` 忽略了 `.local/`、`build/`、`target/` 等目录。Markdown 中写了路径，只代表原机器的存放位置，不代表对应文件已经上传。

- 原始证据：`.local/device-results*/`、`.local/crash-20261023/`、`.local/merge-review/`，以及本文所链报告里引用的其他目录。
- 最新 roco 崩溃采集：本工作区 `.local/roco-ci-bug-20261023/`，包含完整系统日志、应用诊断、安装 APK、驱动库、反汇编和详细分析。
- 测试工具和固定场景：`.local/device-tests/`，及旧记录引用的 `/tmp/ruffle-oom/` 等临时目录。临时目录可能已不存在，不能视为可靠备份。
- 历史 APK 与匹配符号：`build/diagnostics/`、`build/production/`，尤其报告中标注的 `*-symbols/libruffle_android.so`。重新编译的库不能自动视为与旧 APK 严格匹配。
- 签名密钥：`.local/release-signing/`，另见发布文档；必须单独安全备份，不能重新生成来代替旧密钥。
- 本地 SDK/NDK、Gradle/Cargo 缓存：可重新安装或下载，主要影响迁移耗时。`.cargo/config.toml`、`local.properties` 可能含本机路径，新环境应按实际路径重建。

历史排查资料主要位于原工程 `/home/linlin/Code/Rust/ruffle-android/`；最新 roco 采集位于 `/home/linlin/.codex/worktrees/1a03/ruffle-android/`。它们是不同工作区，不会自动同步各自的忽略目录。迁移前应分别检查，而非只复制其中一个 `.local`。

## 换环境时需要保留什么

1. 确认所需源码、文档、脚本已经提交并推送；对仍未提交的内容，先单独备份。仅在 Git stash 或本地分支里的内容也不会由普通 clone 自动恢复。
2. 私下备份相关原始证据目录、测试场景/脚本、APK 和匹配符号库，并记录源码提交、构建版本、ABI、文件 SHA-256 与设备信息。没有匹配符号时应明确标注。
3. 签名密钥与密码单独安全备份。完整 logcat、抓帧和应用诊断可能包含账号、会话或设备信息；公开仓库中只保留经过检查的必要摘录与结论。
4. 新环境先读本索引及相关报告，再恢复需要的证据。无线调试端口会变化，旧记录中的端口不可直接当作当前连接配置。

聊天记录可以补充上下文，但项目接续应以已保存的文档、源码和证据为依据。Git 中的结论足以指导下一步；若遗漏原始证据，详细符号化、重新统计和复核往往需要重新采集。
