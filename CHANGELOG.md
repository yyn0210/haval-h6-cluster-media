# Changelog

本项目的所有重要变更都记录在这里。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增

- 英文 README（`README.en.md`）
- `docs/architecture.md`：链路、属性 ID、取值与权限模型的完整说明
- `docs/ADDING_APPS.md`：接入新音乐 App 的确认流程
- GitHub Actions 自动构建、Issue / PR 模板
- `CONTRIBUTING.md`、`SECURITY.md`、`CHANGELOG.md`
- MIT License

### 变更

- **应用包名改为 `com.janeluo.gwm.clusterbridge`**（原 `com.gwm.clusterbridge`）。
  两个包名互不覆盖，升级前请先卸载旧版并重新授予通知使用权
- 构建产物改名为 `out/haval-h6-cluster-media-v<versionName>.apk`
- 版本号统一以 `app/build.gradle` 为准，`build_apk.py` 自动读取
- 仓库文件统一使用 LF（`.gitattributes`）

## [1.2] - 2026-10-28

### 新增

- 酷我音乐车机版 `cn.kuwo.kwmusiccar` 默认支持
- 通知 Token 兜底通道：从 `Notification.EXTRA_MEDIA_SESSION` 直接建立控制器
- 「可见会话 N 个」诊断日志，直接显示系统里有哪些媒体会话
- 诊断信息对话框列出活跃会话及当前曲目
- 目标包名支持通配符 `*`

### 修复

- MainActivity 与 Service 各自创建 Publisher，导致音源反复 REQUEST / ABANDON（改为进程内单例）
- 系统周期性重绑通知监听时误释放音源，导致仪表闪烁
- `connect()` 并发调用可能建立两个连接

### 变更

- 默认目标包名加入 QQ音乐车机版 `com.tencent.qqmusiccar`
- 老版本"未修改过的默认配置"自动升级，用户自定义的列表不受影响

## [1.1] - 2026-10-28

### 修复

- 车机版 QQ音乐无法桥接：默认包名误用了手机版 `com.tencent.qqmusic`，
  正确值为 `com.tencent.qqmusiccar`

## [1.0] - 2026-10-28

### 新增

- 首个可运行版本
- 通过 `aptiv.multidisplay` 向仪表投送测试数据
- 基于通知监听的 MediaSession 桥接
- `sourceType` 可配置
- 界面实时日志与连接状态
