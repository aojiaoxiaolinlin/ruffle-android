# 游戏不卡 Android

`kabu` 分支是卡布西游的 Android 登录客户端，基于 [Ruffle](https://ruffle.rs)。安装后输入 4399 账号和密码，点击 **登录并进入游戏**。

正式包名为 `rs.ruffle.kabu`，应用名为 **游戏不卡**，可与通用 Ruffle 客户端同时安装。

## 下载与安装

请从 [本仓库 Releases](https://github.com/aojiaoxiaolinlin/ruffle-android/releases) 下载 `kabu` 分支构建，或在 GitHub Actions 的 **Build** 工作流中下载 **kabu-release-apks** artifact。

- artifact 中的 APK 为 `游戏不卡-<ABI>-release.apk`
- 多数现代设备使用 `arm64-v8a`
- 推送 `kabu` 分支会触发 **Build** 工作流；也可以在 **Actions → Build → Run workflow** 中手动选择 `kabu`
- 不要使用上游 Ruffle 的 F-Droid 或 Release 安装包替代本分支构建

正式发布使用仓库 secrets 中的持久签名。没有配置 `KEYSTORE` 的临时构建会使用一次性签名，安装新构建时可能需要先卸载旧版本，从而清除应用数据。

## 主要功能

- 使用 4399 用户名和密码登录，登录成功后把不透明的 `auth_string` 用 Android Keystore 加密保存到应用的 no-backup 目录
- 后续启动显示 **继续游戏**，复用票据，不需要再次提交账号密码；票据是否过期由游戏服务器判断，应用不设置本地过期时间
- 官方返回登录命令会清除票据并回到登录页；普通退出游戏和网络故障不会自动清除票据
- 主页和游戏菜单提供 **重新登录 / 切换账号**
- `GreenGame.swf` 通过 FlashVar 接收票据，并自动配置资源基址
- 保存账号密码是可选的独立加密功能；密码不会写入保存的实例状态或日志
- 菜单按钮打开可拖动的浮动工具栏，工具栏会吸附到最近的屏幕边缘并保持在安全区域内
- 浮动菜单支持 **重新加载**，使用相同游戏和启动参数重启播放器；退出游戏返回主页但不清除已保存会话
- 点击 Flash 文本输入框会打开系统输入法，支持中文组合、已提交的 Unicode 文本、退格和前向删除；键盘覆盖游戏，不改变渲染区域大小

## 已知限制

- 需要 CAPTCHA 验证的账号无法在当前快捷登录页完成验证
- 悬浮键盘的显示和可用性取决于已安装的输入法；百度输入法的游戏键盘模式由输入法自身控制，在测试设备上未能为 Ruffle 激活该模式
- 游戏玩法兼容性仍取决于 Ruffle 和游戏资源本身

## 构建与测试

构建环境和依赖请见 [CONTRIBUTING.md](CONTRIBUTING.md#building-from-source)。

```sh
./gradlew :app:testDebugUnitTest assembleRelease
```

CI 成功后可下载 **kabu-release-apks** artifact，其中的 APK 已重命名为 `游戏不卡-<ABI>-release.apk`。

## 分支与发布

- `main`：保持与上游 Ruffle Android 对齐
- `base`：维护共享 Android 改进
- `kabu`：卡布西游登录客户端
- `roco`：洛克王国登录客户端

发布版本使用 tag，`kabu` 分支名保持不变。

## 与上游 Ruffle 的关系

本分支基于 [Ruffle](https://ruffle.rs) 和 [ruffle-rs/ruffle-android](https://github.com/ruffle-rs/ruffle-android)，但包含本仓库自己的登录、会话和 Android 改动。上游项目、F-Droid 版本和上游 Release 不是本分支的下载渠道。
