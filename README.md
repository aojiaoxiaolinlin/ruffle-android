# 洛克王国 Android

`roco` 分支基于 `kabu`，复用游戏播放器、输入法、浮动工具栏和设置界面。
安装后点击 **进入游戏**，优先使用已保存的游戏会话直接启动；首次使用时在腾讯官方页面完成 QQ 授权。
正式包名为 `rs.ruffle.roco`，本地包名为 `rs.ruffle.roco.local`，可与卡布客户端同时安装。

登录使用同一个 WebView 的 UA、Cookie 和官方页面：
`login.html` → QQ OAuth → 拦截 `logintarget.html?code=…` →
`login3?code=…&platfrom_src=2` → 从官方响应 HTML/JS 中提取 FlashVars。
默认使用官方 mobile 登录页，并提供桌面扫码登录切换。
手机 QQ 按钮使用官方 `pt.qrcode.get(1)`：生成官方二维码会话并唤起 QQ 确认，
原 WebView 的官方轮询器继续等待结果。通过 AndroidX WebKit 的 document-start API，
仅在 `https://xui.ptlogin2.qq.com/cgi-bin/xlogin` 中接入按钮事件。
仅允许携带官方 `qrcode` 的 `wtloginmqq://ptlogin/qlogin` 唤起请求，
移除浏览器返回地址，保留客户端自己的 `schemacallback` 作为返回提示，
该回调只恢复原 WebView，不加载回调中的 URL 或票据；认证信息始终通过原会话轮询获取。
拒绝会将认证结果交给外部浏览器的 `p=` 方式。
QQ 确认后返回应用，会自动读取 HTML 并启动游戏；无需手工复制认证信息。
启动 QQ 时使用 FLAG_ACTIVITY_NEW_TASK，让 QQ 授权页与客户端分处独立任务。
QQ 二维码授权结束时会把其所在任务切到后台；隔离任务后不会再连带隐藏客户端。
已在当前手机 QQ 实测确认后自动返回客户端并进入原生游戏，无需手动再次打开应用。
游戏 OAuth 参数和认证服务保持官方原样，没有集成或冒用腾讯游戏的原生 QQ SDK 身份。
旧 WebView 不支持 document-start 时，可使用扫码或密码登录。
授权码由原生网络层使用相同 UA 和 WebView Cookie 兑换；覆盖 iframe 回调并去重，
DOM 轮询作为兜底，回到前台继续检测。入口使用 `singleTop`，避免桌面重入清除登录页。
应用不绘制 QQ 密码表单。
校验 `angel_uin`、`angel_key`、`skey`、`pskey` 后，通过 Intent/JNI 传给 Ruffle，
加载 `https://res.17roco.qq.com/main.swf`，base 和 config 使用同一资源域名。
票据使用 Android Keystore AES-GCM 加密保存到 noBackupFilesDir，不进入日志或系统备份。
重启、覆盖更新和退出游戏保留会话，不凭空设置本地过期时间；有效性由游戏服务器校验。
官方登录页跳转会清除旧会话并在应用内重新授权；主页和游戏菜单也提供“重新授权 / 切换账号”。
网络故障不会自动清除会话。官方网页登录 Cookie 由 Android WebView 管理并在授权成功后落盘。

官方 main.swf 的舞台为 960×560；Roco 播放强制 ShowAll 和居中对齐，避免 SWF 的左上对齐在宽屏手机上留下单边空白。
SurfaceView 保持全屏，低分辨率渲染只调整缓冲区，不通过 View 变换改变画布和触控位置。

原生 TCP 使用已有的 SocketMode::Allow；ExternalInterface 提供
`get_platfrom_src() -> "2"`，其余页面钩子返回 Undefined。
游戏资源请求启用 HTTP → HTTPS 升级。
应用图标与登录页顶部标识使用登录分析目录提供的 `roco.png` 原图。
登录页背景图片来源：腾讯官方登录背景
`https://res.17roco.qq.com/images/V2/loginbg-20160418.jpg`。

验证：Kotlin 编译、FlashVars 编解码/缺失参数/模板占位符单元测试、Android ARM64 Rust 检查。
已在真机验证启动、官方 QQ 二维码/密码登录入口，以及“一键登录”唤起
`com.tencent.open.agent.QuickLoginAuthorityActivity`。
两个真机回归测试覆盖从桌面返回保留登录 WebView、从 iframe 读取 FlashVars。
会话存储的三项真机测试覆盖 Keystore 加密持久化、损坏回退和无效票据不覆盖有效会话，均已通过。
舞台居中已由用户和截图确认：2800×1280 屏幕左右各留 302px 黑边，游戏内容保持 960:560 比例。
已在授权保存后强制关闭进程，重开并直接复用加密票据，经选服“快速进入”再次进入王国城堡，全程无需 QQ 授权。
已由用户一键授权并进入洛克王国，真机显示登录礼包画面。
正式接入后的再次验证已到原生推荐服务器界面，Activity 栈中只保留主页和 PlayerActivity，
没有登录 WebView Activity。后续玩法兼容性仍需继续验证。
构建工作流包含 `roco` 分支，产物为 **roco-release-apks**。

The general Ruffle documentation below applies to the non-Kabu build unless otherwise stated.

It is in a very early stage.

# Prebuilt APKs

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="75">](https://f-droid.org/packages/rs.ruffle/)

The latest release [(here)](https://github.com/ruffle-rs/ruffle-android/releases) should have a few `.apk` files uploaded as assets.

You can try this app by downloading and installing one of those.

- **For the vast majority of modern phones, tablets, single board computers, and small game consoles, you'll need the `arm64-v8a` version.**

- The `armeabi-v7a` version is for older, 32-bit ARM stuff.

- The `x86_64` version is for some rare Intel/Microsoft tablets and/or for Chromebooks, and/or for running on a PC on Android-x86 or in Waydroid or similar.

- The `x86` version is there mostly just for completeness.

- The `universal` version should work on all 4 of the above architectures, but it's _huge_.

# Building from source

Please see [CONTRIBUTING.md](CONTRIBUTING.md#building-from-source) for details about how to build this repository yourself.

## Loading resources from an HTTP server

On the opening screen, set **Custom base URL (optional)** before selecting a local SWF or opening a SWF URL.
For example, with `http://192.168.1.100:8000/game/` as the base URL, a SWF request for `images/title.png`
loads `http://192.168.1.100:8000/game/images/title.png`. End directory URLs with `/`.
Leave the field empty to use the SWF location. This setting resolves relative URLs; absolute URLs inside the SWF are unchanged.

Use a server address reachable from the Android device. `localhost` and `127.0.0.1` refer to the Android device itself;
for a server running on your computer, use the computer's LAN IP address (or `10.0.2.2` in the standard Android emulator).
HTTP requests are supported without automatically upgrading them to HTTPS.

## Development core and Chinese fonts

This development branch pins all Ruffle crates to commit `96672e20dc02566a6befd17a3a9cd18d9c773de6`
of `aojiaoxiaolinlin/ruffle`. It includes the shared Scale9 rendering, AVM2 broadcast-event propagation,
and default-font glyph fallback fixes used by our desktop build. Update all Ruffle revisions together
and regenerate `Cargo.lock` when changing the core.

The Android player loads device fonts from Android's system, product, vendor, and updated-font directories.
Missing Windows font families use Android alternatives, and Chinese glyphs can fall back to Noto CJK
or Droid Sans Fallback, with simplified Chinese preferred. Font collections retain their face indices
and share file data between faces. No extra font download or storage permission is needed.
Available glyphs still depend on the fonts installed by the device manufacturer.

## Building APKs with GitHub Actions

The repository includes a **Build** workflow in `.github/workflows/build.yml`.
After pushing your changes to your fork, enable Actions if necessary, select **Actions → Build → Run workflow**,
and choose the branch containing your changes. Pushes to `main` and pull requests targeting `main` also trigger the workflow.

Once the `build-apks` job succeeds, download the **ruffle-release-apks** artifact from the workflow run.
It contains APKs for all four supported ABIs and a universal APK. Most current Android devices use `arm64-v8a`.
No signing secrets are required for a personal build: the workflow generates a temporary signing key when `KEYSTORE` is absent.
That key changes between runs, so installing a later build may require uninstalling the previous one, which removes app data.
For consistent signing, configure `KEYSTORE` (base64-encoded keystore), `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`,
and `SIGNING_STORE_PASSWORD` as repository secrets.

---

# TODO

In no particular order:

- [X] Ability to show the system virtual keyboard (softinput), for text input
- [ ] Controller/Gamepad input?
  - Mapped to key presses and/or virtual mouse pointer
- [ ] Own custom keyboard overlay, maybe even per-content configs
- [ ] Error/panic handling
- [ ] Loading "animation" (spinner)
- [ ] Alternative audio backend (OpenSL ES) for Android < 8
- [ ] Proper storage backend?
- [ ] Resolve design glitches/styling/theming (immersive mode, window insets for holes/notches/corners)
- [ ] Publish to various app stores, maybe automatically?
- [ ] Bundle demo animations/games
- [ ] Add ability to load content from well known online collections? (well maybe not z0r... unless?)
- [ ] History, favorites, other flair...?

### DONE:

- [X] Clean up ~everything
- [X] Cross-platform build instructions?
  - I think gradle should take care of it now
- [X] UI backend (context menu)
  - Context menu works
- [X] Logging?
- [X] Navigator backend (fetch, open browser)
  - Opening links works at least
- [X] Touch/mouse input
- [X] Keyboard input: physical keyboard, `scrcpy`, and the system input method
  - This was needed: https://github.com/rust-windowing/winit/pull/2226
- [X] Split into a separate repo
- [X] Add ability to Open SWF by entered/pasted URL (or even directly from clipboard)
  - No direct clipboard open, but easy to paste into the text field...
- [X] Unglitchify rendering: scale, center and letterbox the content properly
- [ ] Ask CPAL/Oboe to open a "media" type output stream instead of a "call" one
  - so the right volume slider controls it, and it uses the loud(er)speaker
  - -> solved by switching to a direct AAudio (ndk-audio) backend
- [X] Add building this to CI, at least to the release workflow
  - This repo has its own CI setup, which builds APKs
- [X] Simplify build process (hook cargo-apk into gradle, drop cargo-apk?)
  - ~cargo-apk is fine, but is only used to detect the SDK/NDK environment and run Cargo in it, and not to build an APK.~
  - actually solved by switching to `cargo-ndk` and the corresponding Gradle plugin
- [X] Somehow filter files to be picked to .swf
  - How well this works depends on the file picker, but it "should work most of the time"
- [X] Unglitchify audio volume (buttons unresponsive?)
  - (pending: https://github.com/rust-windowing/winit/pull/1919)
  - actually solved by switching to GameActivity instead
- [ ] Register Ruffle to open .swf files
  - How well this works depends on the application opening the file, but it "should work most of the time"
- [X] Figure out why videos are not playing (could be a seeking issue)
  - The video decoder features weren't enabled on `ruffle_core`...
- [X] Sign the APK
  - Using a very simple key for now, with just my name in it
- [X] Support for 32-bit ARM phones
  - Untested, but should work in theory
- [X] Support for x86(_64) tablets?
  - Sorted out
- [X] Consider not building the intermediate .apk just for the shared libraries
  - Figured out, no intermediate .apk any more, only native libs built
- [ ] Unbreak the regular build on CI
  - No longer relevant after the repo split
- [ ] Clean up commit history of the branch
  - No longer relevant after the repo split
