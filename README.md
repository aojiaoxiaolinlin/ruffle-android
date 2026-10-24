# 洛克王国 Android

`roco` 分支基于 `kabu`，复用游戏播放器、输入法、浮动工具栏和设置界面。安装后点击 **进入游戏**，优先使用已保存的游戏会话直接启动；首次使用时在腾讯官方页面完成 QQ 授权。

正式包名为 `rs.ruffle.roco`，应用名为 **洛克王国**；本地构建包名为 `rs.ruffle.roco.local`，诊断构建包名为 `rs.ruffle.roco.diagnostics`。本客户端可与 `rs.ruffle.kabu`（游戏不卡）同时安装。

## 下载与安装

请从 [本仓库 Releases](https://github.com/aojiaoxiaolinlin/ruffle-android/releases) 下载 `roco` 分支构建，或在 GitHub Actions 的 **Build** 工作流中下载 **roco-release-apks** artifact。

- artifact 中的 APK 为 `洛克王国-<ABI>-release.apk`
- 多数现代设备使用 `arm64-v8a`
- 推送 `roco` 分支会触发 **Build** 工作流；也可以在 **Actions → Build → Run workflow** 中手动选择 `roco`
- 不要使用上游 Ruffle 的 F-Droid 或 Release 安装包替代本分支构建

正式发布使用仓库 secrets 中的持久签名。没有配置 `KEYSTORE` 的临时构建会使用一次性签名，安装新构建时可能需要先卸载旧版本，从而清除应用数据。

## 主要功能

- 首次使用在官方页面完成 QQ 授权，之后优先复用已保存会话直接进入游戏
- 默认使用官方 mobile 登录页，支持切换到桌面扫码登录
- 手机 QQ 按钮通过官方 `pt.qrcode.get(1)` 生成二维码会话并唤起 QQ 确认，原 WebView 继续轮询结果
- 授权成功后自动返回客户端并启动原生游戏，不需要手工复制认证信息
- 票据使用 Android Keystore AES-GCM 加密保存，不进入日志或系统备份
- 重启、覆盖更新和退出游戏会保留会话；官方登录页跳转会清除旧会话并要求重新授权
- 主页和游戏菜单提供 **重新授权 / 切换账号**
- 官方 `main.swf` 舞台为 960×560；播放强制 ShowAll 和居中对齐，避免宽屏手机上出现单边空白
- 菜单按钮打开可拖动的浮动工具栏，支持重新加载；点击 Flash 文本输入框会打开系统输入法
- 游戏资源请求启用 HTTP → HTTPS 升级，原生 TCP 使用已有的 `SocketMode::Allow`

## 登录与会话

登录使用同一个 WebView 的 UA、Cookie 和官方页面：

`login.html` → QQ OAuth → 拦截 `logintarget.html?code=…` → `login3?code=…&platfrom_src=2` → 从官方响应 HTML/JS 中提取 FlashVars。

手机 QQ 按钮只在 `https://xui.ptlogin2.qq.com/cgi-bin/xlogin` 中通过 AndroidX WebKit 的 document-start API 接入。仅允许携带官方 `qrcode` 的 `wtloginmqq://ptlogin/qlogin` 唤起请求；移除浏览器返回地址，保留客户端自己的 `schemacallback` 作为返回提示。该回调只恢复原 WebView，不加载回调中的 URL 或票据；认证信息始终通过原会话轮询获取。拒绝授权时，认证结果交给外部浏览器的 `p=` 方式。

启动 QQ 时使用 `FLAG_ACTIVITY_NEW_TASK`，让 QQ 授权页与客户端分处独立任务。QQ 二维码授权结束时会把其所在任务切到后台；隔离任务后不会连带隐藏客户端。游戏 OAuth 参数和认证服务保持官方原样，没有集成或冒用腾讯游戏的原生 QQ SDK 身份。

旧 WebView 不支持 document-start 时，可使用扫码或密码登录。授权码由原生网络层使用相同 UA 和 WebView Cookie 兑换；覆盖 iframe 回调并去重，DOM 轮询作为兜底，回到前台继续检测。入口使用 `singleTop`，避免桌面重入清除登录页。应用不绘制 QQ 密码表单。

校验 `angel_uin`、`angel_key`、`skey`、`pskey` 后，通过 Intent/JNI 传给 Ruffle，加载 `https://res.17roco.qq.com/main.swf`，base 和 config 使用同一资源域名。

夜间保护时段官方会返回不含票据的提示页（包含 `//res.17roco.qq.com/swf/night.swf` 和 `parents.shtml` 链接）。客户端识别 `night.swf` 后不会把它载入登录 WebView，也不会写入或清除已保存会话，而是直接以 `https://res.17roco.qq.com/swf/night.swf` 启动播放器并显示 Toast 提示；该提示页同样使用 Roco 的 ShowAll 和居中对齐。

票据使用 Android Keystore AES-GCM 加密保存到 `noBackupFilesDir`，不进入日志或系统备份。重启、覆盖更新和退出游戏保留会话，不凭空设置本地过期时间；有效性由游戏服务器校验。网络故障不会自动清除会话。官方网页登录 Cookie 由 Android WebView 管理并在授权成功后落盘。

## 渲染与兼容

- 官方 `main.swf` 的舞台为 960×560；Roco 播放强制 ShowAll 和居中对齐
- SurfaceView 保持全屏，低分辨率渲染只调整缓冲区，不通过 View 变换改变画布和触控位置
- 原生 TCP 使用 `SocketMode::Allow`
- ExternalInterface 提供 `get_platfrom_src() -> "2"`，其余页面钩子返回 Undefined
- 游戏资源请求启用 HTTP → HTTPS 升级
- 应用图标与登录页顶部标识使用登录分析目录提供的 `roco.png` 原图；登录页背景来自腾讯官方登录资源

## 验证记录

- Kotlin 编译、FlashVars 编解码/缺失参数/模板占位符单元测试、Android ARM64 Rust 检查已通过
- 已在真机验证启动、官方 QQ 二维码/密码登录入口，以及“一键登录”唤起 `com.tencent.open.agent.QuickLoginAuthorityActivity`
- 两个真机回归测试覆盖从桌面返回保留登录 WebView、从 iframe 读取 FlashVars
- 会话存储的三项真机测试覆盖 Keystore 加密持久化、损坏回退和无效票据不覆盖有效会话
- 舞台居中已由用户和截图确认：2800×1280 屏幕左右各留 302px 黑边，游戏内容保持 960:560 比例
- 已在授权保存后强制关闭进程，重开并直接复用加密票据，经选服“快速进入”再次进入王国城堡，全程无需 QQ 授权
- 已由用户一键授权并进入洛克王国，真机显示登录礼包画面
- 正式接入后的再次验证已到原生推荐服务器界面，Activity 栈中只保留主页和 PlayerActivity，没有登录 WebView Activity

## 已知限制

- 后续玩法兼容性仍需继续验证
- 登录流程依赖官方页面、WebView 和 QQ 客户端行为；官方页面变更可能影响授权
- 旧 WebView 不支持 document-start 时只能使用扫码或密码登录

## 构建与测试

构建环境和依赖请见 [CONTRIBUTING.md](CONTRIBUTING.md#building-from-source)。

```sh
./gradlew :app:testDebugUnitTest assembleRelease
```

CI 成功后可下载 **roco-release-apks** artifact，其中的 APK 已重命名为 `洛克王国-<ABI>-release.apk`。发布时使用 **Make a Release** 工作流并选择对应的 `roco` Build 运行。

## 分支与发布

- `main`：保持与上游 Ruffle Android 对齐
- `base`：维护共享 Android 改进
- `kabu`：卡布西游登录客户端
- `roco`：洛克王国登录客户端

发布版本使用 tag，`roco` 分支名保持不变。

## 与上游 Ruffle 的关系

本分支基于 [Ruffle](https://ruffle.rs) 和 [ruffle-rs/ruffle-android](https://github.com/ruffle-rs/ruffle-android)，但包含本仓库自己的登录、会话和 Android 改动。上游项目、F-Droid 版本和上游 Release 不是本分支的下载渠道。
