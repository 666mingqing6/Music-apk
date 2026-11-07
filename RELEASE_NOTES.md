## HeoMusic Android 1.1.0

把纯静态音乐站封装为「界面在 WebView、播放在原生内核」的 Android 客户端。

### 核心特性

- **原生前台播放服务**：Activity / WebView 被系统回收后音乐继续播放，并自动连播下一首
- **常驻通知与锁屏控制**：`MediaStyle` 通知 + `MediaSession`（耳机线控、蓝牙、车机）
- **后台保活组合**：前台服务 + `PARTIAL_WAKE_LOCK` + `WifiLock` + `NetworkCallback` 断线重连 + `START_STICKY` 状态恢复
- **完整界面还原**：歌词、封面、搜索、加权洗牌「平均随机」、账户与播放计数云同步
- **离线首屏**：Font Awesome 与全部前端资源本地打包，不依赖 CDN
- **数据互通**：与网页版共用同一账号与播放计数（meting-api + D1）

### 安装

下载 `HeoMusic.apk` 安装。使用自签名调试证书，需要允许「未知来源」安装。

```bash
adb install -r HeoMusic.apk
```

### 首次启动

请依次完成三项授权，否则锁屏或长时间播放可能被系统中断：

1. 通知权限（常驻播放通知）
2. 忽略电池优化
3. 厂商设置中的「自启动 / 后台运行 / 锁定任务」

### 系统要求

Android 8.0（API 26）及以上，安装包约 1.1 MB。

### 已知限制

- 尚未进行真机自动化测试，运行时行为需在设备上确认
- 音源来自第三方接口，仅适合个人自用，不建议上架应用商店
- Web Audio 音效改为原生 `Equalizer` / `BassBoost` / `Virtualizer` 近似映射
- 使用调试签名，正式分发请替换为自己的 keystore

### 反馈

如遇播放中断、通知栏异常或界面不同步，请附上 `adb logcat -s HeoMusic` 日志提交 Issue。
