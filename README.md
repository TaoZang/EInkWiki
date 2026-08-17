# 墨水维基（EInkWiki）

一个面向 Android 墨水屏设备的只读 ZIM 客户端。APK 本身不包含百科数据；用户通过局域网从电脑浏览器导入任意 `.zim` 文件，然后在设备上完成本地搜索和全文阅读。

应用不限定 ZIM 的下载来源，不访问内容目录，也不联网查询文件哈希。接收完成后只检查文件能否作为 ZIM 打开并读取；多个文件可以共存，并由用户指定首页使用的搜索库。

源码公开在 [TaoZang/EInkWiki](https://github.com/TaoZang/EInkWiki)，正式 APK 通过 [GitHub Releases](https://github.com/TaoZang/EInkWiki/releases/latest) 分发。

## 已实现

- 默认进入极简首页，搜索框固定居中；从当前书库随机显示三个条目并每分钟刷新
- 书库页启动一个仅在当前局域网监听的 HTTP 上传页，电脑无需 ADB 或额外软件
- 浏览器以 8 MiB 分块传输，显示进度与实时速度；同名、同大小文件可从未完成位置继续
- 支持任意来源的 ZIM、多文件共存、删除，并可明确切换唯一的“当前搜索库”
- 接收完成后只做 ZIM 结构可读性检查，不计算或联网查询 MD5/SHA
- 使用 `libkiwix 2.6.0` / `libzim` 做中文全文搜索；无全文索引时回退到标题建议
- WebView 只从当前 ZIM 读取 HTML、CSS、字体和图片，拒绝外部网络资源
- JavaScript、DOM Storage、数据库、媒体和所有页面动画均禁用
- 纯黑白排版、内置 Noto Serif SC 衬线正文、缩小的文章标题、静态边框和静态进度条
- 整页翻页按钮，兼容物理 Page、音量、左右方向和 Navigate 翻页键
- 正文阅读和局域网导入期间保持屏幕常亮，结束后恢复系统休眠
- 90%–150% 正文字号调节
- 不申请传统外部存储权限，也不申请 `MANAGE_EXTERNAL_STORAGE`
- 只在用户点击后检查 GitHub Releases；更新包经 SHA-256、包名、版本和签名证书复核后交给系统安装器

## 安装与更新

从 [最新 GitHub Release](https://github.com/TaoZang/EInkWiki/releases/latest) 下载 `EInkWiki-vX.Y.Z.apk`。首次安装时，Android 可能要求允许浏览器安装未知来源应用。

正式版的书库页提供“应用更新”面板。它不会在启动时联网，也不会后台轮询；只有点击“检查更新”后才会访问 `TaoZang/EInkWiki` 的 GitHub Release。发现新版本后，应用会下载同名 `.sha256` 文件并验证 APK，随后由 Android 系统安装器请求用户确认。调试版使用不同包名和调试证书，因此明确禁用该入口，不能覆盖正式版。

应用升级不会主动删除已下载的 ZIM 离线包和阅读设置。发布证书必须保持不变，否则 Android 和应用内校验都会拒绝覆盖安装。

正式 release 证书 SHA-256 指纹：

```text
0e1e49f9526b8b626a46d0b571db77bbe45e1c7635031c9ddd56553278ef6224
```

## 导入 ZIM

在书库页点击“通过局域网导入 ZIM”，保持 App 在前台和屏幕常亮，然后在同一局域网的电脑浏览器打开屏幕显示的地址。选择 `.zim` 文件后即可传输；中断后重新选择同一文件会从已接收位置继续。一次服务会顺序接收多个文件，完成后点击“停止导入”。

应用只接受 `.zim` 扩展名，并在完成时尝试打开和读取一个条目。它不会判断内容来自哪个网站，也不会验证远端 MD5/SHA。文件保存在应用专属外部目录的 `offline/packs/` 下；卸载应用时 Android 会删除该目录及书库。

## 构建

要求：JDK 17、Android SDK Platform 35、Android Build Tools 35.x。项目使用 Gradle 8.9 和 Android Gradle Plugin 8.7.3。

```bash
./gradlew test lintDebug assembleDebug
```

调试 APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

`assembleRelease` 生成未签名的 release APK。正式发布由 GitHub Actions 使用仓库中配置的固定证书完成 zipalign、签名、验签、SHA-256 生成和 Release 创建。直接开发调试请使用上述带调试签名的 debug APK。

APK 只包含 `arm64-v8a` 和 `armeabi-v7a` native library，覆盖目标墨水屏设备；不包含 x86 模拟器 ABI。要跑 native 冒烟测试，请连接 ARM Android 设备后执行：

```bash
./gradlew connectedDebugAndroidTest
```

项目使用 Android 15/API 35 ARM64 16 KB 页大小模拟器验证原生 ZIM 读取、全文搜索和阅读。历史测试记录和截图见 [artifacts/emulator-test-20260817/README.md](artifacts/emulator-test-20260817/README.md)。

## 代码结构

```text
app/src/main/java/org/einkwiki/app/
├── MainActivity.java              单 Activity UI 与状态编排
├── EInkProgressView.java          无动画静态进度条
├── library/                       本地 ZIM 书库与无动画列表适配器
├── transfer/                      浏览器局域网分块上传服务
├── reader/                        ICU/libzim、搜索、ZIM 资源与 WebView
└── update/                        GitHub Release、APK 校验与系统安装器
```

更完整的产品边界和后续路线见 [docs/PRODUCT_DESIGN.md](docs/PRODUCT_DESIGN.md)。

## 发布

正式版本使用规范标签 `vX.Y.Z`。标签构建会把 `X.Y.Z` 写入 `versionName`，并用 GitHub Actions 的单调递增 run number 写入 `versionCode`。新仓库需先配置以下 Actions secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

签名文件和密码不得提交到 Git。首次发布前应为 EInkWiki 创建独立证书并做离线备份；丢失证书后无法对现有安装进行覆盖升级。完整步骤见 [docs/RELEASING.md](docs/RELEASING.md)。

## 数据来源与许可

- 应用读取用户自行取得的 ZIM；内容来源、署名和许可信息以具体文件及条目页面为准。
- 本项目因链接 GPL 的 `libkiwix`，整体以 GPL-3.0 发布，见 [LICENSE](LICENSE)。
- ICU 数据、Noto Serif SC 字体和其他第三方组件说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- GPL-3.0、Apache-2.0、ICU 58 与 SIL Open Font License 的完整许可副本位于 [licenses/](licenses/)，并作为可读 assets 打入 APK。
- 每个 Release 标签对应当版 APK 的完整项目源码和构建脚本；GitHub 自动提供该标签的源码压缩包。
