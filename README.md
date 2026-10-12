# AutoNet — WiFi/移动数据自动切换

Android 自动网络切换工具（Shizuku 特权执行），规则驱动：

- **类型 1：WiFi 无网络切数据** — WiFi 已连接但无网持续 N 秒 → 开移动数据，网络恢复后回滚
- **类型 2：指定 SSID** — 连上/离开指定 WiFi 时执行动作
- **类型 3：定时切换** — 按时间段切换
- **类型 4：弱信号自动重选** — RSSI 低于阈值持续 N 秒 → 系统级重选 WiFi（可在已保存网络中重新选优），可附加数据兜底；恢复稳定 60 秒后自动回滚

规则引擎带冷却时间、条件去抖、动作回滚（invert 执行）与日志时间线（`files/autonet.log`）。

## 构建

```bash
# 需要 ANDROID_HOME / local.properties 指向 SDK，aapt2 使用 build-tools 35.0.0
gradle assembleDebug
```

依赖：AGP 8.9.0 / Gradle 8.11.1 / compileSdk 35。

## 权限

- Shizuku（授权后以 shell/adb 身份执行 `svc wifi` / `svc data` 等）
- 状态栏通知、开机自启（引擎常驻）

## 版本

- v1.0.6 (versionCode 7)：修复弱信号类型选择导致的 `ArrayIndexOutOfBoundsException` 崩溃
- v1.0.5：新增弱信号自动重选规则
