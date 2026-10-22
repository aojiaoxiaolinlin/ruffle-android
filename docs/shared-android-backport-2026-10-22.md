# Android 公用基础能力回移（2026-10-22）

目标分支 `codex/android-development`，起点 `4fc43ff`。从游戏分支提取公用能力，保持基础应用的文件/URL 打开入口、Ruffle 品牌与 `rs.ruffle` / `rs.ruffle.local` 包名。

## 范围

- 补齐通用 baseUrl 覆盖的读取、HTTP/HTTPS 校验与影片 URL 回退；不包含游戏登录参数。
- 系统 IME 与 Flash 文本输入桥接、Unicode/UTF-16 删除处理、覆盖式键盘及对应仪器测试。回移 `eff4ee3` 的公用内容，并保留已修正的测试源码。
- 所有 Ruffle 依赖与 Cargo.lock 固定到核心 `0.7.1-A` 合并提交 `533ef0884fd87fbd117467bb0ea82d50c4904681`。
- 前端画质/MSAA 上限应用到屏幕及离屏绘制；启用 90 秒闲置可重建资源回收策略。核心包含局部混合裁剪、滤镜降采样及有界缓存。
- 普通构建每 5 秒基础日志，详细内存/纹理统计通过 Cargo `diagnostics` 显式开启；保留崩溃记录和退出期任务兼容处理。
- 基础诊断构建 `-PdiagnosticBuild` 使用 `rs.ruffle.diagnostics` / `Ruffle (Diagnostics)`，启用诊断 feature；普通正式包名保持不变。

游戏登录、auth_string 注入、账号保存、游戏图标/名称、特定域名网络策略及游戏专属 UI 均留在 kabu。已有实机性能报告属于游戏前端的验证证据，不代表基础文件/URL 入口已经进行了同场景真机测试。

完成验证后将基础分支合回 kabu，使基础改进拥有共享祖先；kabu 继续保留自己的包名与品牌。

## 本次验证

- Android arm64 Clippy 默认配置与全部 features（包含 JPEG XR、详细诊断和测试目标）均通过 `-D warnings`。
- 基础分支 3 项 JVM 单元测试通过；系统键盘等仪器测试 Kotlin 源码编译通过。本次未重跑手机仪器测试。
- 全仓库 ktlintCheck、Rust 格式检查与 git diff --check 通过。
- 核对基础 MainActivity、启动图标与原分支一致，app_name 为 Ruffle，源码不含游戏登录/认证参数或域名网络策略。
- 保持基础分支现有 CI 工作流与通用 Ruffle 产物命名；上述 JVM 测试在本次本地验证中执行。

首次全功能检查缺少 libclang 搜索路径，配置 NDK 自带库后通过；本地命令需将 LIBCLANG_PATH 指向 NDK 的 toolchains/llvm/prebuilt/linux-x86_64/musl/lib（CI 如已有系统 libclang，无需此路径）。
