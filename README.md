# Lenshelf

Lenshelf 是一款 Android 分类相机：拍摄前选择分类，照片、Live 图和视频会自动保存到用户授权根目录中的同名文件夹。应用完全在本地运行，不申请网络权限。

本项目为vibe coding产品，主设计师为GPT-5.6sol。

设计初心为方便长期坚持记录，将非同类记录保存在不同文件夹中，免去整理的烦恼。

- 当前版本：**0.6.5**（versionCode 33）
- 系统要求：**Android 10（API 29）及以上**
- 许可证：[Apache License 2.0](LICENSE)

## 核心功能

- 新建、排序、停用、恢复、导入、重命名和删除分类；重命名可同步修改分类文件夹。
- 分类卡片显示文件夹内直属照片和视频数量，并可跳转到系统文件管理器。
- CameraX 普通拍照：4:3 同范围取景、缩放、闪光、点按对焦和曝光补偿。
- 长按快门录制有声视频：1080p 优先、720p 回退，支持暂停、继续、前后摄切换和同步防抖能力降级。
- Android 13+ 实验性连续有声 Live：Camera2 与 MediaCodec 持续预录，可按约 500 ms 间隔接受多张独立 Motion Photo。
- Room 持久保存队列：采集、封装、SAF 写入和完整读回校验互不阻塞，进程中断后可恢复可用材料。
- 媒体直接保存到用户选择的目录；分类删除默认保留已有文件，只有明确勾选后才同步删除目录内容。

## 系统兼容性

| 系统 | 普通照片 | 独立视频 | 连续 Live |
|---|---|---|---|
| Android 10–12 | 支持 | 支持 | 不启用，界面会说明系统限制 |
| Android 13+ | 支持 | 支持 | 实验性支持，取决于镜头组合与编码能力 |

Live 的分辨率、关键帧间隔、音画同步和图库播放行为由设备及系统实现共同决定。功能会在能力不足时明确降级，不应把某一台手机的测试结果视为所有设备的性能承诺。

已验证边界：

- Xiaomi 17 Pro：主摄 1×、明亮场景下完成 10 组共 50 张连续 Live，50/50 保存并解码成功；音画同步、前摄旋转、切镜头、跨分类、进程恢复和 10 分钟预录后连拍已验证。
- vivo Z5、Android 10：8000×6000 普通 JPEG、1080×1920 有声短视频、SAF 保存和数据库迁移已验证；该系统不启用连续 Live 后端。

## 隐私与存储

Lenshelf 不包含账号、广告、统计或云同步，也不申请网络权限。应用只访问相机、可选麦克风，以及用户通过 Storage Access Framework 明确授权的目录。

照片和视频保存在授权目录中，卸载应用不会自动删除这些媒体。完整说明见[隐私说明](docs/PRIVACY.md)。

## 构建

### 环境

- JDK 17
- Android SDK Platform 35
- Android SDK Build Tools 35

### Windows

在项目根目录创建不提交到 Git 的 `local.properties`：

```properties
sdk.dir=C\:\\Android\\Sdk
```

然后执行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

首次制作正式签名 APK 时，在自己的 PowerShell 终端运行：

```powershell
.\tools\build-release.ps1
```

脚本会在仓库之外创建 `Lenshelf/signing/lenshelf-release.p12`（位于当前用户的 LocalAppData），交互式要求设置密码，并输出 `app/build/outputs/apk/release/Lenshelf-v0.6.5.apk`。密码不会写入项目或脚本。务必将密钥文件和密码分别安全备份；以后的正式版必须使用同一密钥签名。调试签名版不能直接由正式签名版覆盖安装，卸载旧应用前请先确认应用数据与媒体备份。

### macOS / Linux

设置 `ANDROID_HOME` 或创建 `local.properties`，然后执行：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

GitHub Actions 会在每次推送和 Pull Request 时执行相同的测试、Lint 和 Debug 构建。CI 产物只用于验证；正式 APK 通过 GitHub Releases 发布，不提交到源码仓库。

## 使用方法

1. 首次启动时选择一个可长期访问的根目录。
2. 新建分类，或从根目录已有的一级文件夹导入分类。
3. 在首页选择分类进入相机；短按快门拍照，长按快门开始录像。
4. Android 13+ 可使用取景页顶部的 Live 开关；未授权麦克风时可以无声拍摄。
5. 保存成功以目标文件完成读回校验为准。存在待恢复任务时，可在首页重试或处理失败记录。

## 工程结构

```text
app/src/main/         正式应用源码、清单和资源
app/src/debug/        仅 Debug 构建使用的 Camera2 验证探针
app/src/test/         JVM 单元测试
app/src/androidTest/  Room、SAF 和界面仪器测试
docs/                 领域词汇、品牌和隐私说明
tools/                Motion Photo、采集日志和音画同步分析脚本
branding/             品牌源素材
```

当前公开文件清单共 **107 个**；发生文件增删时应重新统计。

## 文档

- [文档索引](docs/README.md)
- [领域模型](docs/DOMAIN_MODEL.md)
- [品牌名称](docs/BRAND-NAME.md)
- [隐私说明](docs/PRIVACY.md)
- [版本记录](CHANGELOG.md)

## 已知限制

- Live 是跨品牌实验功能；Android 10–12 不启用当前需要传感器时间基准的连续 Live 后端。
- 小米图库播放 Motion Photo 时可能引入额外音频延迟；当前补偿只依据已验证的小米设备，系统或图库升级后需复测。
- 低光、闪光、空间不足、SAF 授权失效、锁屏/来电收束和更多厂商设备仍需专项验证。
- 当前没有应用内图库、逐个媒体删除、RAW/DNG、Ultra HDR、云同步或后台持续录制服务。

## 参与和发布

主仓库由项目维护者管理。外部用户可以 Fork，并通过 Issue 或 Pull Request 提议修改；是否合并和发布由维护者决定。贡献要求见 [CONTRIBUTING](CONTRIBUTING.md)。

正式发布前应确认版本号和 CHANGELOG 一致，完成自动测试、Lint、真机回归、Release 签名和 APK 校验。Release keystore、密码、签名配置和 APK 不进入 Git 历史。

## License

Copyright 2026 Lenshelf project

Licensed under the [Apache License, Version 2.0](LICENSE).
