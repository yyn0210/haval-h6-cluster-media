# 接入一个新的音乐 App

本工程不针对具体 App 写适配代码，只要对方使用**标准 MediaSession**，
就可以通过包名接入。下面是完整的确认流程。

## 1. 确认包名

### 方法一：从 APK 读（最准）

```bash
# build-tools 里的 aapt2
aapt2 dump badging "QQ音乐.apk" | head -1
# package: name='com.tencent.qqmusiccar' versionCode='...' versionName='...'
```

Windows 下 aapt2 在 `%ANDROID_SDK_ROOT%\build-tools\<ver>\aapt2.exe`。

PowerShell 里若中文路径报错，先把 APK 复制到纯英文路径再执行。

### 方法二：从车机上列

```bash
adb shell pm list packages -3          # 第三方应用
adb shell pm list packages | grep -i qq
```

`pm list packages` 只有包名，不带应用名，需要自己对照。

### 方法三：从日志里抓

播放时打开日志，看媒体通知来自哪个包：

```bash
adb logcat -s ClusterBridge
# 可见会话 1 个: com.tencent.qqmusiccar(PLAYING)"七里香"
```

或者用系统诊断：

```bash
adb shell dumpsys media_session | grep -E "package=|state=PlaybackState"
```

## 2. 确认它走标准 MediaSession

最直接的证据是系统里有它的媒体会话：

```bash
adb shell dumpsys media_session | grep -A8 com.example.music
```

正常情况下能看到：

- `package=com.example.music`
- `state=PlaybackState {state=3, position=..., speed=1.0}`（3 = PLAYING）
- `metadata: size=..., description=...`

如果这里完全没有它，但播放时确实有媒体通知，说明会话可能存在但未被系统列为"活跃"，
本工程的**通知 Token 通道**仍然可以接管（见 `docs/architecture.md` 第 7.1 节）。

还可以看通知是否使用 `MediaStyle`：

```bash
adb shell dumpsys notification --noredact | grep -A20 com.example.music
# 找 android.mediaSession 或 template=MediaStyle
```

## 3. 加入目标名单

两种方式：

- **界面里改**：打开 App，把包名加到输入框（每行一个），点「保存配置」，
  然后关闭再打开通知使用权（或重启车机）让服务重新扫描。
- **改默认值**：在 `ClusterBridgeService.DEFAULT_PACKAGES` 里追加，
  这样新安装的用户默认就支持。

如果只是自己用，第一种即可，不必改代码。

## 4. 验证

```bash
adb logcat -s ClusterBridge ClusterPublisher
```

期望看到：

```
可见会话 1 个: com.example.music(PLAYING)"歌名"
已接管媒体会话: com.example.music（来源：会话列表）
元数据: 歌名 / 歌手 / 专辑
曲目已上报: 歌名 - 歌手 《专辑》
```

判断顺序：

1. `可见会话 0 个` → App 没在播，或系统没把它算作活跃会话
2. 会话里有包名但没「已接管」→ 包名没加进目标名单
3. 有「已接管」但仪表不动 → 看是否有 `notifyValue propertyId 18` 日志；
   有则是仪表侧 `sourceType` 问题，没有则是控制器回调没触发

## 5. 已知包名

| App | 包名 | 依据 |
|---|---|---|
| QQ音乐 车机版 | `com.tencent.qqmusiccar` | `MediaSessionCompat` + `MediaBrowserService` + `MediaButtonReceiver`；待实车反馈 |
| 酷我音乐 车机版 | `cn.kuwo.kwmusiccar` | `cn.kuwo.mod.mediaSession.KwMediaSessionService` 声明 `android.media.browse.MediaBrowserService`，用 `Notification.MediaStyle`；待实车反馈 |
| QQ音乐 手机版 | `com.tencent.qqmusic` | 标准 MediaSession；车机上通常装不上 |
| 酷狗音乐 | `com.kugou.android` | 标准 MediaSession；未实车验证 |
| 喜马拉雅 | `com.ximalaya.ting.android` | 标准 MediaSession；未实车验证 |

> 「依据」来自 APK 静态分析，不等同于实车跑通。目前实车验证过的只有仪表通道本身。

## 6. 提交 PR 时请附上

- 车型、车机固件版本、App 版本号
- 包名，以及你是怎么确认的（`aapt2 dump badging` 输出或 `dumpsys` 片段）
- `adb logcat -s ClusterBridge ClusterPublisher` 的关键几行（可打码歌名）
- 是否需要额外配置（例如特殊 `sourceType`）

> 请不要在 PR 里附带第三方 APK、固件或受版权保护的媒体文件。
