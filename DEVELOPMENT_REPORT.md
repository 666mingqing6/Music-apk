# HeoMusic Android 客户端开发报告

- 项目：HeoMusic for Android（`com.heomusic.player`）
- 上游：`666mingqing6/Music`（前端）、`666mingqing6/meting-api`（后端）
- 版本：1.1.0（versionCode 2）
- 日期：2026-11-07

---

## 一、背景与目标

上游是一个零构建的纯静态单页应用：`index.html` + `config.js` + `player.js`（2,400 行）+ `auth.js` + `playstats.js` + `modern.css`（1,907 行），共约 6,500 行、615 KB。音频由浏览器 `<audio>` 元素播放，状态全部存放在 localStorage，后端是独立的 Cloudflare Worker（909 行 ES Module + D1）。

迁移目标有两条硬性要求：

1. 交付可安装的 APK，界面与交互尽量 1:1 还原；
2. **音乐必须能够长时间后台播放，锁屏、切后台、界面被回收都不能中断**。

第二条直接决定了技术路线：纯 WebView 壳无法满足，因为 WebView 依附于 Activity，界面销毁后音频随之停止。

## 二、迁移路线评估

| 路线 | 改造量 | UI 保真 | 后台播放 | 结论 |
| --- | --- | --- | --- | --- |
| 在线 URL WebView 壳 | 极低 | 100% | 无 | 仅适合快速验证 |
| 本地 assets + 原生 WebView 壳 | 低 | 100% | 需原生服务 | 采用 |
| Capacitor / Ionic | 低 | 95%+ | 依赖插件 | 备选，引入 Node 工具链 |
| TWA / PWA | 低 | 100% | 无 | 排除：需 Chrome + Digital Asset Links，国内不可行 |
| Flutter / RN 重写 | 高 | 需重做 | 好 | 排除：1,907 行 CSS 与音效链复刻成本过高 |
| 原生 Kotlin + Media3 全量重写 | 最高 | 需重做 | 最好 | 暂不采用：2,400 行播放逻辑需整体移植 |

最终采用**路线 2 的混合形态**：WebView 承载全部界面，播放内核下沉到原生前台服务。这样既保留了已打磨成熟的 UI、歌词、加权洗牌、账户同步逻辑，又拿到了原生后台播放能力。后端零改动，Web 与 APK 共用同一套账号与播放计数。

## 三、客户端架构

```text
MainActivity
  ├── WebView（https://appassets.androidplatform.net/）
  │     ├── shouldInterceptRequest → assets/www 映射（含 font/woff2 MIME）
  │     ├── bridge.js：把 <audio> 代理为原生播放器的镜像
  │     └── native-patch.js：接管 loadTrack / 音效 / MediaSession
  ├── JsBridge（@JavascriptInterface）：UI → 服务指令
  └── BridgeHub：服务 → UI 状态回推（WebView 为空时静默丢弃）

MusicService（前台服务）
  ├── MediaPlayer + 自持队列
  ├── MediaSession + MediaStyle 常驻通知
  ├── AudioFocus / PARTIAL_WAKE_LOCK / WifiLock
  ├── NetworkCallback + 指数退避重连
  └── SharedPreferences 状态持久化 + AlarmManager 重建
```

### 3.1 为什么用 `shouldInterceptRequest` 而不是 `WebViewAssetLoader`

`WebViewAssetLoader` 属于 AndroidX，而本项目刻意不引入任何 AndroidX/Maven 依赖（构建环境无 Gradle）。因此手写拦截器把 `appassets.androidplatform.net` 映射到 `assets/www`，效果等价，且可以精确控制 MIME 与 CORS 头。

不使用 `file://` 的原因：`file://` 页面的 origin 为 `null`，跨域 `fetch` 与 `Authorization` 预检会失败，账户同步不可用。

### 3.2 播放内核的接管方式

不修改 `player.js` 的业务逻辑，只在它之前注入 `bridge.js`，对真实 `<audio>` 元素做属性/方法遮蔽：

- `src` setter → `JsBridge.playTrack(url, title, artist, cover, index)`
- `play()` / `pause()` → 原生服务
- `currentTime` / `duration` / `volume` / `paused` / `buffered` / `error` → 由服务回推的状态驱动
- 服务每秒推送状态，`bridge.js` 派发 `timeupdate` / `progress` / `play` / `pause`，原有进度条、歌词、封面逻辑无需改动

这样 `player.js` 完全感知不到播放器已经被替换。

### 3.3 服务自持队列

`native-patch.js` 在每次 `loadTrack` 时把完整播放列表同步给服务。服务因此具备独立切歌能力：通知栏、锁屏、耳机按键触发的上一首/下一首不依赖 WebView。

曲目播完时：

1. 服务先向 WebView 发 `ended`，由 JS 按加权洗牌选出下一首；
2. 若 3 秒内没有新的 `playTrack`（界面已被回收），服务自行按队列续播。

这是「界面被杀仍能连续播放」的关键。

### 3.4 保活体系

| 层次 | 实现 |
| --- | --- |
| 前台服务 | `startForeground` + `foregroundServiceType="mediaPlayback"` + `START_STICKY` |
| 通知 | `MediaStyle`、`setOngoing(true)`、4 个动作按钮 |
| 系统控制 | `MediaSession`（锁屏 / 耳机 / 蓝牙 / 车机） |
| CPU | `PARTIAL_WAKE_LOCK`，播放期间持有 |
| 网络 | `WifiLock`（API 29+ 低延迟，以下高性能）+ `NetworkCallback` |
| 焦点 | 短暂丢失自动暂停，恢复后自动续播；永久丢失 2.5 秒后重新申请 |
| 恢复 | 状态每 10 秒落盘；`stopWithTask="false"`；`onTaskRemoved` + `AlarmManager` 重建 |
| 用户停止 | 通知栏「停止」写入持久化标记，之后不再自动复活 |

明确未采用：无声 `AudioTrack`、双进程互拉、1 像素 Activity、JobScheduler 轮询。这些手段在新版 Android 上失效，且违反应用商店政策。

## 四、问题与修复记录

### 4.1 首版构建阶段

| 问题 | 根因 | 修复 |
| --- | --- | --- |
| `aapt2 link` 报 `resource does not override` | 手动构建缺少 overlay 语义 | 增加 `--auto-add-overlay` |
| `WIFI_MODE_FULL_HIGH_PERF_LOCK` 编译失败 | 该常量已从 API 35 SDK 移除 | 按版本改用 `WIFI_MODE_FULL_LOW_LATENCY` / `WIFI_MODE_FULL_HIGH_PERF` |
| 未播放时抢占音频焦点 | `onCreate` 无条件 `requestFocus` | 仅在恢复播放或真正开始播放时申请 |

### 4.2 真机反馈阶段

| 问题 | 根因 | 修复 |
| --- | --- | --- |
| 图标残缺、播放按钮图标错误 | 首版用 Unicode 字符替代 Font Awesome，字形与语义均不正确 | 完整本地化 Font Awesome 6.5.1（CSS + 4 个 woff2），并补 `font/woff2` MIME |
| 应用图标粗糙 | 首版为手绘矢量音符 | Unsplash 音乐照片 + Font Awesome 字形合成 adaptive icon，主色对齐 `#3b82f6` |
| 未主动弹出授权窗口 | 只弹说明列表，让用户自行寻找设置 | 改为自动串行：通知权限系统弹窗 → 电池优化系统弹窗 → 厂商自启动设置页 |

### 4.3 二次审视阶段

| 隐患 | 后果 | 修复 |
| --- | --- | --- |
| Activity 重建后从第一首重播 | 回到 App 突然跳歌，与服务状态冲突 | 检测服务状态后拦截首曲，轮询歌单就绪后恢复 index 与原进度 |
| `initVolume()` 触发音量 setter | 打开 App 即启动空的前台服务 | 新增 `sendIfRunning()`，音量/暂停/seek/音效不再拉起服务 |
| 后台通知栏切歌后界面不同步 | 回 App 显示旧曲、歌词错位 | `onState` 检测 index 变化并跟随，带 1.5 秒用户操作保护窗口 |
| 停止后被 WebView 的 pause 重新拉起 | 通知栏「停止」失效 | `stopEverything()` 立即置空服务单例 |
| `START_STICKY` 重建不区分用户意图 | 用户停止后仍可能复活 | null intent 时检查持久化的停止标记 |
| 后台每秒注入 JS | 长时间播放额外耗电 | `onPause` 先同步冲刷计数再断开桥接 |
| 二进制资源编码错误 | 字体/图片可能解码异常 | 非文本 MIME 的 `encoding` 传 `null` |

## 五、验证方法与结果

| 项目 | 方法 | 结果 |
| --- | --- | --- |
| 资源编译与链接 | `aapt2 compile` / `aapt2 link` | 通过 |
| Java 编译 | `javac -source 8 -target 8` | 通过（仅弃用 API 警告） |
| dex 生成 | `d8 --min-api 26` | 通过，45 KB |
| 签名 | `apksigner verify` | v2 / v3 通过 |
| 清单 | `aapt2 dump badging` / `xmltree` | 包名、权限、`mediaPlayback`、`stopWithTask=false` 均正确 |
| 图标 | badging + APK 条目 | adaptive icon 与 5 组密度齐全 |
| 桥接逻辑 | `node --check` + 最小 DOM 模拟 | `onState` / `src` / `play` / `pause` / `ended` 全部通过 |
| 资源引用 | 解析 HTML 本地引用 | 12 个引用全部存在 |
| 真机运行 | — | **未验证**（本机无 adb 设备） |

产物：`HeoMusic.apk`，1.1 MB，36 个条目，`resources.arsc` 保持未压缩。

## 六、风险与遗留

1. **合规风险**：音源为网易云非公开接口与未授权直链，仅适合个人自用；上架应用商店需先取得授权。
2. **未做真机验证**：WebView 对 `<audio>` 的属性遮蔽依赖运行时行为，需在真机确认进度、歌词、切歌、锁屏控制的完整链路。
3. **厂商 ROM 差异**：自启动与后台锁定入口因品牌而异，`openAutoStartSettings()` 已覆盖主流品牌，未覆盖的会回退到应用详情页。
4. **音效保真度**：原生 `Equalizer` / `BassBoost` / `Virtualizer` 只能近似还原 Web Audio 的 6 种效果。
5. **锁屏封面缺失**：`MediaMetadata` 暂未设置 `ALBUM_ART`，锁屏只显示标题与歌手。
6. **通知进度**：未在通知栏展示播放进度条，避免高频刷新通知带来的耗电。

## 七、版本记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| 1.0.0 | 2026-11-07 | 首个可安装版本：原生前台播放服务、全套保活、WebView 桥接 |
| 1.1.0 | 2026-11-07 | 恢复 Font Awesome 原生图标；替换应用图标；授权流程改为自动串行；修复 6 项运行时隐患 |

## 八、构建环境

- JDK 17（OpenJDK）
- Android SDK Platform 35
- Android Build-Tools 35.0.0
- 无 Gradle、无 Maven、无 AndroidX
- 构建命令：`bash build.sh`
