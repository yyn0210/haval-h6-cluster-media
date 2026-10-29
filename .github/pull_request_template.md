## 改动内容

<!-- 一句话说明这个 PR 做了什么 -->

## 类型

- [ ] 功能（feat）
- [ ] 修复（fix）
- [ ] 文档（docs）
- [ ] 构建 / 工具链（chore）
- [ ] 重构（refactor）

## 验证方式

<!-- 真机日志、截图、构建输出都可以；没有车机环境请写明 -->

```bash
python build_apk.py --clean
adb install -r out/haval-h6-cluster-media-v1.2.apk
adb logcat -s ClusterBridge ClusterPublisher
```

## 检查清单

- [ ] 构建通过，`out/` 下生成了 APK
- [ ] 没有提交 `build/`、`out/`、`*.apk`、`debug.keystore`
- [ ] 没有包含第三方 APK、固件或受版权保护的内容
- [ ] 如果改了 `DEFAULT_PACKAGES`，同步检查了 `LEGACY_DEFAULTS` 迁移
- [ ] 如果改了采集/投送逻辑，附了真机日志
- [ ] 文档已同步更新（README / CHANGELOG / docs）

## 关联 Issue

<!-- Closes #123 -->
