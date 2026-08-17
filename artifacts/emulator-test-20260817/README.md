# Android 模拟器基本流程测试

测试日期：2026-08-17

## 环境

- Android Emulator 37.1.11
- Android 15 / API 35
- ARM64 Google APIs 16 KB page-size system image
- 系统页大小：16,384 bytes
- 分辨率：1080 × 1920
- 内存：2 GiB
- 系统动画缩放全部设为 0

## 结果

1. 冷启动成功，初始书库显示“尚未下载”。
2. 点击下载后，从应用配置的 Kiwix 官方 URL 完成真实下载和应用内校验。
3. ZIM 文件大小为 43,883,567 bytes；独立 `sha256sum` 为
   `3a25f1e50da3f20d5c63bb54fdb7cfaf0d5af03656d7fc83511bd300bf9dbbbd`。
4. 设备端 JNI 冒烟测试通过：打开小型 ZIM、全文搜索 `test`、读取 `main.html`（1/1）。
5. 打开真实化学 ZIM 后搜索 `DNA`，显示 50 条结果。
6. 成功打开“DNA纳米技术”全文；“下一页”产生一次整页滚动。
7. 返回后搜索词与结果仍保留；返回书库正常。
8. 强制停止并冷启动应用后，书库仍直接显示“可以离线阅读”。
9. Android crash log buffer 为空。

## 截图

- [全新书库](01-library.png)
- [下载并校验完成](02-downloading.png)
- [搜索页](03-search.png)
- [DNA 搜索结果](04-results.png)
- [阅读首屏](05-reader-top.png)
- [整页翻页后](06-reader-next.png)
- [进程重启后状态恢复](07-relaunch-ready.png)

## 未覆盖

- 模拟器无法评估真实墨水屏的残影、局刷、触控延迟和耗电。
- 下载速度较快，未能稳定截取“下载中”和“正在校验”两个短暂状态，但最终文件、哈希和 ready 状态均已独立验证。
