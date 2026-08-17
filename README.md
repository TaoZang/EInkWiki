# 墨水维基（EInkWiki）

一个面向 Android 墨水屏设备的只读维基百科客户端。APK 本身不包含百科数据；用户安装后从 Kiwix 官方目录下载独立的 OpenZIM 离线包，然后在设备上完成搜索和全文阅读。

当前是可运行的 MVP：书库从 Kiwix 官方 OPDS 目录读取全部中文 Wikipedia 包，支持同时安装多个 ZIM，并由用户指定首页使用的搜索库。APK 仍不包含百科数据；无网时会显示上次缓存目录，并保留一个约 41.9 MiB 的中文化学无图包作为首次兜底项。

源码公开在 [TaoZang/EInkWiki](https://github.com/TaoZang/EInkWiki)，正式 APK 通过 [GitHub Releases](https://github.com/TaoZang/EInkWiki/releases/latest) 分发。

## 已实现

- 默认进入只有关键词输入框的极简首页；右上角“书库”用于下载和管理离线包
- 进入书库时按需更新 Kiwix 官方中文 Wikipedia 目录；当前目录包含全库、热门和 11 个主题系列的 mini / nopic / maxi 变体
- 支持多个离线包共存、逐包下载/取消/校验/删除，并可明确切换唯一的“当前搜索库”
- 使用系统 `DownloadManager` 下载，应用退出或进程重启后仍可继续由系统管理
- 下载进度、已下载容量和实时速度显示，以及取消和失败重试
- 文件大小、SHA-256、libzim 可读性三重校验，再从 `.partial` 原子激活为 `.zim`
- 使用 `libkiwix 2.6.0` / `libzim` 做中文全文搜索；无全文索引时回退到标题建议
- WebView 只从当前 ZIM 读取 HTML、CSS、字体和图片，拒绝外部网络资源
- JavaScript、DOM Storage、数据库、媒体和所有页面动画均禁用
- 纯黑白排版、衬线正文、静态边框和静态进度条
- 整页翻页按钮，兼容物理 Page、音量、左右方向和 Navigate 翻页键
- 正文阅读期间以及书库存在活动下载时保持屏幕常亮，结束或离开后恢复系统休眠
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

## 离线包目录与兜底包

应用访问 Kiwix 的[中文 Wikipedia OPDS v2 目录](https://opds.library.kiwix.org/catalog/v2/entries?lang=zho&category=wikipedia&count=-1)。目录元数据会缓存在本机；用户点击某个包的“下载”后，应用再读取该条目的官方 `.meta4`，取得精确文件大小与 SHA-256，校验信息准备完成后才交给系统下载。

`mini` 是精简无图内容，`nopic` 是完整正文无图，`maxi` 是完整正文含图。书库只展示每个系列的当前版本；已安装的旧版本描述会单独保留，不会因远端目录换月而消失。

首次无缓存且目录不可达时使用以下兜底项：

| 字段 | 值 |
|---|---|
| 名称 | 中文维基百科 · 化学 · 无图 |
| 文件 | `wikipedia_zh_chemistry_nopic_2026-06.zim` |
| 大小 | 43,883,567 bytes（41.9 MiB） |
| SHA-256 | `3a25f1e50da3f20d5c63bb54fdb7cfaf0d5af03656d7fc83511bd300bf9dbbbd` |
| 下载源 | [Kiwix 官方目录](https://download.kiwix.org/zim/wikipedia/wikipedia_zh_chemistry_nopic_2026-06.zim) |

应用将包保存到应用专属外部目录的 `offline/packs/` 下。卸载应用时，Android 会删除该目录及离线包。

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

项目已在 Android 15/API 35 ARM64 16 KB 页大小模拟器上完成 31 项目录加载、双包共存、13.5 MiB 包实际下载与校验、搜索库切换、进程重启恢复、全文搜索及阅读测试。此前单包流程的测试记录和截图见 [artifacts/emulator-test-20260817/README.md](artifacts/emulator-test-20260817/README.md)。

## 代码结构

```text
app/src/main/java/org/einkwiki/app/
├── MainActivity.java              单 Activity UI 与状态编排
├── EInkProgressView.java          无动画静态进度条
├── data/                          离线包目录、版本和校验状态
├── download/                      Android DownloadManager 封装
├── library/                       离线包行状态与无动画列表适配器
├── reader/                        ICU/libkiwix、搜索、ZIM 资源与 WebView
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

- Kiwix/OpenZIM 从 Wikimedia 项目内容制作 ZIM，应用仅下载和读取该独立数据文件。
- 维基百科文本通常按 CC BY-SA 4.0 和 GFDL 提供，具体署名与许可信息以离线条目所带页面信息为准。
- 本项目因链接 GPL 的 `libkiwix`，整体以 GPL-3.0 发布，见 [LICENSE](LICENSE)。
- ICU 数据和其他第三方组件说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- GPL-3.0、Apache-2.0 与 ICU 58 的完整许可副本位于 [licenses/](licenses/)，并作为可读 assets 打入 APK。
- 每个 Release 标签对应当版 APK 的完整项目源码和构建脚本；GitHub 自动提供该标签的源码压缩包。
