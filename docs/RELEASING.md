# 发布说明

EInkWiki 通过 `TaoZang/EInkWiki` 的 GitHub Actions 构建，并通过 GitHub Releases 分发正式 APK。发布标签和资产名是应用内更新协议的一部分，不可随意更改。

## 一次性配置

1. 为 EInkWiki 单独生成长期 release keystore，并至少做一份离线备份。不要复用调试证书，不要将 keystore 或密码提交到仓库。
2. 将 keystore 做单行 Base64 编码。
3. 在 GitHub 仓库 Actions secrets 中配置：
   - `ANDROID_KEYSTORE_BASE64`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
4. 保护发布标签和 Actions 配置。首个正式版发布后不得更换 application ID `org.einkwiki.app` 或签名证书。

release 证书 SHA-256 指纹为：

```text
0e1e49f9526b8b626a46d0b571db77bbe45e1c7635031c9ddd56553278ef6224
```

它可以使用 Android SDK 的 `apksigner verify --print-certs` 从已签名 APK 复核。当前本机专用证书保存在工作区已忽略的 `.signing/einkwiki-release.p12`，随机密码保存在 macOS 钥匙串服务 `org.einkwiki.app.release-keystore`；仍需另做一份证书离线备份。

## 发布新版本

1. 确认 `main` 已通过本地测试，工作区干净。
2. 更新变更说明，并以规范三段版本号创建标签，例如 `v0.1.1`。
3. 推送提交和标签：`git push origin main && git push origin v0.1.1`。
4. GitHub Actions 会运行 lint、单元测试、debug/release 构建、zipalign 和签名验证。
5. 成功后 Release 会包含且只依赖以下更新资产：
   - `EInkWiki-v0.1.1.apk`
   - `EInkWiki-v0.1.1.apk.sha256`
6. 在 ARM 设备上从上一正式版本走一次“检查更新 → 下载并校验 → 系统安装器”回归，确认离线包和设置保留。

工作流先创建 draft、上传并核对 APK 与 SHA-256 两项资产，最后才公开。若中途失败，可修复原因后重新运行：工作流只会修复同标签的残留 draft；一旦 Release 已公开，就会拒绝替换其资产。不要替换已经分发的同版本 APK。

## 本地发布构建

以下命令只生成未签名 APK，主要用于预检：

```bash
./gradlew -PversionName=0.1.1 -PversionCode=2 lintRelease testReleaseUnitTest assembleRelease
```

正式分发必须由固定 release keystore 签名。debug APK、未签名 APK或由其他证书签名的 APK 均不能作为 GitHub Release 更新源。
