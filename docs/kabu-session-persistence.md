# kabu 登录会话保存（2026-10-23）

登录成功后将完整、不透明的 auth_string 加密保存到 noBackupFilesDir/kabu_session，密钥由 Android Keystore 管理，AES-GCM 加密，AtomicFile 写入。票据不进入 SharedPreferences、Compose saved instance state、SWF URL 或应用新增日志。密码是否保存仍由原有“记住账号密码”选项控制，两者独立。

下次启动显示“继续游戏”，直接以已保存的 auth_string 作为 FlashVar 启动游戏，不提交账号密码、不重复请求 4399 登录接口，不自行推测过期时间。重新加载与普通退出保留会话。首页和游戏菜单提供“重新登录 / 切换账号”；只有主动重登、存储损坏/密钥失效或游戏请求返回登录入口时才清除票据。网络请求失败或 socket 断开本身不触发本地会话删除。

## 游戏请求重新登录

本次只读取公共游戏容器和启动 SWF，没有使用真实账号执行登录。官方容器位于 http://enter.wanwan4399.com/bin-debug/KBgameindex.html；启动 SWF 中可以确认登录提示后的回跳目标是该容器或 http://www.4399.com/flash/48399.htm，使用固定的 window.parent.location.href 赋值形式。应用只识别这两个明确入口，不运行任意 JavaScript。

原来的通用导航后端会直接丢弃 javascript URL，且游戏部分重登回调检查 ExternalInterface.available。本次为官方卡布 SWF 增加最小页面接口兼容（浏览器身份返回空字符串，不冒充 AIR 客户端），并在通用导航层之前转交固定回跳指令到 PlayerActivity。其他网页导航、资源 fetch 和 socket 继续按原后端处理。遇到明确回跳时清除会话，回到 MainActivity 显示登录表单；保留另行勾选保存的账号密码，便于重新提交。

回跳 URL 不是服务器拒绝原因的完整证明，游戏也可能在用户确认其他提示后要求回到登录页。本实现以“游戏明确请求重新登录”为触发条件，不按通用网络错误文本、帧率、计时器或未知 socket 数据猜测过期。若某个失效场景只显示提示而不请求回跳，可使用菜单中的重新登录入口；尚未用真实失效票据验证全部游戏提示路径。

## 验证范围

JVM 测试覆盖原样保留转义参数、拒绝无签名/截断的票据、仅接受官方回跳并拒绝注入及其他网页。无 UI 的 Android 存储测试覆盖加密持久化与重开读取、损坏回退、无效新票据不覆盖已有会话。实际执行结果以本次检查及对应 CI 为准；编译通过不等于已实机验证票据生命周期。

本次公共客户端分析资料在排查工作区被 Git 忽略的 .local/kabu-session-review/；实现与测试改动在 kabu 分支，不修改 roco/base。

## 本次执行结果

- `:app:testDebugUnitTest`：16 项通过，0 失败；Android 测试 Kotlin 编译和全量 ktlintCheck 通过。
- ARM64 Clippy `-D warnings`：普通配置及 CI 的 jpegxr 特性均通过。验证在不携带本机 `.cargo/config.toml` path 覆盖的临时副本中执行，锁文件保持远端核心提交，只新增 async-channel 的直接依赖项。
- 独立包名 `rs.ruffle.kabu.sessiontest` 在已连接 Android 16 设备执行 KabuSessionStoreTest：3/3 通过，验证加密重开读取、损坏回退与无效票据不覆盖有效会话。使用纯测试票据，没有提交真实账号密码；验证结束已卸载两个临时测试包。
- 票据服务器过期后的真实游戏路径、原生导航到登录 Activity 的完整交互尚未实机复测。没有新增依赖桌面或 WebView 的 UI 集成测试；全量模拟器结果仍以本次 CI 为准。
- 测试原始输出存于排查工作区 `.local/kabu-session-validation/store-tests.txt`，本机 SDK/临时包名通过临时 Gradle init 脚本指定，未写入正式构建配置。
