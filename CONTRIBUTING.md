# 参与贡献

感谢愿意帮忙。这个项目最需要的是**实车测试反馈**，其次才是代码。

## 最有用的反馈

开 Issue 时请附上：

- 车型、年款、车机固件版本（例如「三代 H6，4038」）
- 本 App 版本（界面标题下方或 `adb shell dumpsys package com.gwm.clusterbridge | grep versionName`）
- 目标音乐 App 的包名和版本
- 关键日志：

  ```bash
  adb logcat -s MultiDisplay.Service ClusterBridge ClusterPublisher
  ```

- 期望行为 vs 实际行为

歌名等信息可以打码，但请保留「可见会话 N 个」「已接管媒体会话」「notifyValue propertyId」这几类行。

## 环境准备

- JDK 8+（推荐 17）
- Android SDK：`platforms/android-28` + 任一 `build-tools`
- Python 3.6+（离线构建脚本）
- 不是必须，但建议装 Android Studio 方便看布局

Gradle 不是必需；本仓库默认用 `build_apk.py` 直接调用 SDK 工具链。

## 构建与自测

```bash
python build_apk.py --clean
adb install -r out/haval-h6-cluster-media-v*.apk
```

提交前至少确认：

1. 构建通过，`out/` 下生成 APK
2. 安装后「仪表通道：已连接」
3. 点「发送测试数据到仪表」，日志出现 `notifyValue propertyId 18`
4. 如果改动涉及 MediaSession 采集，附一段真机 logcat

没有车机环境也可以贡献：文档、英文翻译、构建脚本兼容性（Linux / macOS）都有价值。

## 代码约定

- Java 8 语法，不引入第三方依赖（车机是离线环境，且要控制体积）
- 4 空格缩进；注释用中文，写在"为什么"而不是"是什么"
- `aptiv.multidisplay` 的存根只能加在 `aptiv-stubs/`，**不能**打包进 APK
- 不要"修正"原厂 API 的拼写：`onConncted()`、`MediaPlayStatus` 等
- 改动 `DEFAULT_PACKAGES` 时，记得同步 `LEGACY_DEFAULTS` 的迁移逻辑
- 不要把调试日志写成每帧都刷屏；进度类日志要节流

## 提交信息

使用 Conventional Commits：

```
feat: 支持网易云音乐车机版
fix: 暂停时不再推进度
docs: 补充封面投送的实现说明
chore: 升级 CI 的 build-tools
```

一个提交做一件事。格式化改动（换行、空格）单独提交，不要混在功能里。

## Pull Request

1. Fork → 新建分支（`feat/xxx`、`fix/xxx`）
2. 提交前跑一次 `python build_apk.py --clean`
3. PR 描述里写清楚：问题、做法、验证方式（真机日志最佳）
4. 涉及 UI 的改动请附截图

PR 模板会自动带出检查清单。

## 不要提交

- `build/`、`out/`、`*.apk`
- `debug.keystore`（首次构建会自动生成）
- 厂商固件、系统镜像、第三方 APK
- 受版权保护的歌词、封面、音频
- 个人隐私信息（VIN、手机号、账号、完整日志里的个人信息）

## 版本发布（维护者）

1. 改 `app/build.gradle` 的 `versionCode` / `versionName`（这是唯一版本来源）
2. 把 `CHANGELOG.md` 的 `[Unreleased]` 改成新版本号与日期
3. 提交并打 tag：

   ```bash
   git tag v1.2
   git push origin main --tags
   ```

4. `.github/workflows/release.yml` 会自动构建并把 APK 挂到 Release

## 行为准则

- 对事不对人，欢迎不同实现思路
- 涉及逆向的讨论只围绕「自己的车辆」和「已公开的接口行为」
- 不讨论绕过 DRM、破解付费内容、分发他人作品

## 安全问题

不要开公开 Issue，见 [SECURITY.md](SECURITY.md)。
