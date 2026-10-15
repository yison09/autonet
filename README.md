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
./gradlew assembleDebug     # 或使用系统 gradle 8.11.1
```

依赖：AGP 8.9.0 / Gradle 8.11.1 / compileSdk 34。

## 权限

- Shizuku（授权后以 shell/adb 身份执行 `svc wifi` / `svc data` 等）
- 状态栏通知、开机自启（引擎常驻）

## 版本

- v1.0.7 (versionCode 8)：按代码审查报告修复高危与中危项
  - **双卡切换数据**：`ITelephony.setUserDataEnabled` 反射签名修正为按 Android 版本分级
    （13+ `setDataEnabledForReason(int,int,boolean,String)` → 12 `(int,int,boolean)` →
    11 `setUserDataEnabled(int,boolean)` → 8.1 `setDataEnabled(int,boolean)`），
    旧代码把参数顺序写成 `(int,boolean,int)`，在 Android 12+ 上必然失败
  - **默认数据卡**：新增 `ISub.setDefaultDataSubId` 真·切卡，失败才回退逐卡开关
  - **ShizukuProvider 权限**：补 `android.permission.INTERACT_ACROSS_USERS_FULL`，
    否则 provider 暴露在无权限校验的通道上
  - **Shizuku Manager 包名**：跳转目标修正为 `moe.shizuku.privileged.api`
    （`moe.shizuku.manager` 只是 namespace，不是 applicationId）
  - **定时规则**：窗口结束立即回滚（不依赖 autoRevert），并精确唤醒到窗口边界，
    去掉 `computeNextWake()` 的 1Hz 忙轮询（省电）
  - **活动规则回收**：停用/删除/改动动作配置时先回滚已执行的动作，避免"数据一直开、无人回收"
  - **其他**：Shizuku 未授权提示每条件周期只提示一次；`run()` 加超时看门狗；
    `setWifiEnabled`/`setDataEnabled` 回读校验；定时规则未选生效日时给出明确提示；
    弱信号阈值文案澄清（-60 dBm 最灵敏）
- v1.0.6 (versionCode 7)：修复弱信号类型选择导致的 `ArrayIndexOutOfBoundsException` 崩溃
- v1.0.5：新增弱信号自动重选规则
