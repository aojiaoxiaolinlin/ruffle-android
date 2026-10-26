# Ruffle Android（base 分支）

[中文](#中文) | [English](#english)

## 中文

`base` 是本仓库的共享 Android 开发分支，基于 [Ruffle](https://ruffle.rs)。它维护通用 SWF 播放器、中文输入与字体回退、HTTP 资源基址、崩溃诊断和设备测试等通用改进。经过本仓库的本地优化和改进，`base` 的运行稳定性和性能得到大幅提升。`base` 依赖的 Ruffle 核心也经过本仓库优化，来自 [aojiaoxiaolinlin/ruffle](https://github.com/aojiaoxiaolinlin/ruffle)，不是官方 Ruffle 核心。

`base` 不是官方 Ruffle Android，也不通过 F-Droid 分发。请只从本仓库的 Releases 或 GitHub Actions 获取构建。

### 下载与安装

请从 [本仓库 Releases](https://github.com/aojiaoxiaolinlin/ruffle-android/releases) 下载 `base` 分支构建，或在 GitHub Actions 的 **Build** 工作流中下载 **ruffle-release-apks** artifact。

- 正式包名：`rs.ruffle`；应用名：`Ruffle`
- 本地构建包名：`rs.ruffle.local`；诊断构建包名：`rs.ruffle.diagnostics`
- artifact 中的 APK 为 `app-<ABI>-release.apk`，包含 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 和 `universal`
- 多数现代设备使用 `arm64-v8a`
- 不要使用上游 Ruffle 的 F-Droid 或 Release 安装包替代本分支构建

### 主要功能

- 系统输入法支持中文组合、Unicode 文本、退格和前向删除；键盘覆盖在内容上方，不改变渲染区域大小
- 从 Android 的 system、product、vendor 和 updated-font 目录加载设备字体；中文可回退到 Noto CJK 或 Droid Sans Fallback，并优先简体中文
- 在打开 SWF 前可设置 **Custom base URL (optional)**，用于解析 SWF 中的相对资源地址；支持 HTTP，不会自动升级为 HTTPS
- 本仓库优化的 Ruffle 核心包含 Scale9 渲染、AVM2 broadcast event 传播和默认字体 glyph fallback 等修复
- 包含 Android 崩溃诊断、OOM 排查和设备测试文档

### 构建与测试

构建环境和依赖请见 [CONTRIBUTING.md](CONTRIBUTING.md#building-from-source)。

```sh
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
```

推送 `base` 或 `main` 会触发 **Build** 工作流；也可以在 **Actions → Build → Run workflow** 中手动选择 `base` 运行。构建成功后下载 **ruffle-release-apks** artifact。

正式发布使用仓库 secrets 中的持久签名。没有配置 `KEYSTORE` 的临时构建会使用一次性签名，安装新构建时可能需要先卸载旧版本，从而清除应用数据。

### 分支与发布

- `main`：保持与上游 Ruffle Android 对齐
- `base`：维护共享 Android 改进
- `kabu`：卡布西游登录客户端
- `roco`：洛克王国登录客户端

发布版本使用 tag。`base` 分支名保持不变。

### 已知限制

- 不通过 F-Droid 分发，也不使用上游 Ruffle 的签名
- 中文 glyph 的可用性取决于设备厂商预装字体
- 通用 SWF 兼容性仍以 Ruffle 上游实现为准

### 与上游 Ruffle 的关系

本分支的 Android 工程基于 [Ruffle](https://ruffle.rs) 和 [ruffle-rs/ruffle-android](https://github.com/ruffle-rs/ruffle-android)，但包含本仓库自己的 Android 改动；依赖的 Ruffle 核心来自 [aojiaoxiaolinlin/ruffle](https://github.com/aojiaoxiaolinlin/ruffle)，并非官方 Ruffle 核心。上游项目、F-Droid 版本和上游 Release 不是本分支的下载渠道。

## English

`base` is the shared Android development branch of this repository, built on [Ruffle](https://ruffle.rs). It maintains general improvements such as the SWF player, Chinese input and font fallback, HTTP resource base URLs, crash diagnostics, and device testing. Local optimizations and improvements from this repository have significantly improved its runtime stability and performance. The Ruffle core used by `base` is also optimized in this repository at [aojiaoxiaolinlin/ruffle](https://github.com/aojiaoxiaolinlin/ruffle), and is not the official Ruffle core.

This is not the official Ruffle Android app, and it is not distributed through F-Droid. Use only builds from this repository's Releases or GitHub Actions.

### Download and install

Download `base` builds from [this repository's Releases](https://github.com/aojiaoxiaolinlin/ruffle-android/releases), or download the **ruffle-release-apks** artifact from a **Build** workflow run.

- Release package: `rs.ruffle`; app label: `Ruffle`
- Local build package: `rs.ruffle.local`; diagnostic build package: `rs.ruffle.diagnostics`
- Artifact APKs are named `app-<ABI>-release.apk` and cover `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, and `universal`
- Most modern devices should use `arm64-v8a`
- Do not substitute the upstream Ruffle F-Droid or Release package for a build from this branch

### Features

- System input method support for Chinese composition, Unicode text, backspace, and forward delete; the keyboard overlays the content without resizing the render surface
- Device fonts are loaded from Android's system, product, vendor, and updated-font directories; Chinese can fall back to Noto CJK or Droid Sans Fallback, preferring Simplified Chinese
- An optional **Custom base URL** resolves relative resources before opening a local SWF or SWF URL; HTTP is supported and is not automatically upgraded to HTTPS
- The optimized Ruffle core maintained in this repository includes fixes for Scale9 rendering, AVM2 broadcast-event propagation, and default-font glyph fallback
- Android crash diagnostics, OOM investigation, and device-test documentation

### Build and test

See [CONTRIBUTING.md](CONTRIBUTING.md#building-from-source) for the build environment and dependencies.

```sh
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
```

Pushing to `base` or `main` triggers the **Build** workflow. You can also run it manually with **Actions → Build → Run workflow** and select `base`. Download the **ruffle-release-apks** artifact after a successful build.

Official releases use the persistent signing key configured in repository secrets. Temporary builds without `KEYSTORE` use a one-off signing key, so installing a newer build may require uninstalling the previous one and clearing app data.

### Branches and releases

- `main`: aligned with upstream Ruffle Android
- `base`: shared Android improvements
- `kabu`: Kabu Xiyou login client
- `roco`: Roco Kingdom login client

Releases use tags. The `base` branch name stays unchanged.

### Limitations

- Not distributed through F-Droid, and not signed with the upstream Ruffle key
- Chinese glyph availability depends on the fonts preinstalled by the device vendor
- General SWF compatibility still follows the upstream Ruffle implementation

### Upstream Ruffle

This branch's Android project is based on [Ruffle](https://ruffle.rs) and [ruffle-rs/ruffle-android](https://github.com/ruffle-rs/ruffle-android), with additional Android changes maintained in this repository. Its Ruffle core comes from the optimized [aojiaoxiaolinlin/ruffle](https://github.com/aojiaoxiaolinlin/ruffle), not the official Ruffle core. The upstream project, its F-Droid package, and its Releases are not download channels for this branch.
