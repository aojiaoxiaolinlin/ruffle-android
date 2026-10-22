# Android 发布与覆盖更新

从本次发布基线开始，正式游戏包固定为 `rs.ruffle.kabu`，基础 Ruffle 包为 `rs.ruffle`。诊断与 local 包是独立应用，不作为正式发布 APK。无需兼容之前使用随机密钥发布的版本。

## 签名只创建一次

正式发布密钥保存在工程 `.local/release-signing/`，已被 Git 忽略。整个目录都需要安全备份到工程之外：包括 `android-release.jks` 和密码文件。不能因为换电脑、清理工作目录而重新生成签名。不要上传到源码、聊天或 APK 下载页。

首次初始化可执行 `python3 scripts/create-release-signing.py`，脚本在目录非空时拒绝覆盖。已有密钥必须复用。

安装 GitHub CLI，并使用有仓库 Secrets 写入权限的账号执行 `gh auth login`，随后：

```sh
sh scripts/configure-github-signing.sh aojiaoxiaolinlin/ruffle-android
```

该命令上传 `KEYSTORE`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD` 和 `SIGNING_CERT_SHA256` 五项 Actions Secrets，不在终端打印内容。也可以在仓库 Settings → Secrets and variables → Actions 手动创建同名 Secrets，值取自该目录的同名文件。

CI 在非 PR 构建开始时检查配置；缺少任何项目就失败，绝不生成随机密钥。生成 APK 后还会检查其签名指纹是否匹配。PR 只验证构建和测试，不上传正式 APK，也不创建签名密钥。

## 版本号自动增长

`gradle.properties` 保存基线：

- `releaseVersionCode=26102200`
- `releaseVersionName=0.261022`

Build 工作流使用基线加 `GITHUB_RUN_NUMBER` 作为内部版本号，显示版本号追加同一构建序号，例如 `0.261022.123`。ABI APK 仍沿用原有的 `内部版本号 × 10 + ABI 编号`，因此每一架构的后续版本都递增。

不要下调基线、重置/重建 Build 工作流后直接发布，或安装编号更低的历史构建。重新运行同一 CI run 是同一版本；新一次 Build run 才是新版本。旧的手工 Bump version 工作流已删除，避免两套版本规则互相覆盖。

本地正式包也必须使用上述同一密钥，并指定正确的版本号：

```sh
export ANDROID_HOME=/path/to/android-sdk
sh scripts/build-local-release.sh 26102324 0.261022.124
```

示例数字仅说明参数格式。正式使用时，代码必须高于所有已发布 Build 使用的内部版本号。如果本地发布使用了更高编号，应在发布前同步提高 CI 基线，保证下一次 CI 构建仍然递增。

## 发布

1. 推送 `kabu`（游戏）或 `base`（基础播放器），等待 Build 以及 Android Tests 成功。
2. 手动运行 Make a Release，选择对应分支，填入该次成功 Build 的 run ID。
3. 工作流只下载该 run 的 APK，并核对分支、源码提交、签名和实际 APK 版本，创建草稿 Release。
4. 检查草稿后公开发布。用户下载对应 APK，直接选择安装/更新，不要先卸载，也不要清除应用数据。

覆盖安装保留应用数据目录，但登录是否仍有效还取决于游戏服务器会话与游戏自身的登录逻辑。当前不包含应用内检查更新/下载界面。
