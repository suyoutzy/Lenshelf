# Lenshelf

Lenshelf 是一款 Android 分类相机：拍摄前选择分类，照片和视频保存到用户授权根目录中的同名文件夹。文档照片先调整、预览，确认后再归档。应用完全在本地运行，不申请网络权限。

本项目为vibe coding产品，主设计师为GPT-5.6sol。

设计初心为方便长期坚持记录，将非同类记录保存在不同文件夹中，免去整理的烦恼。

- 当前源码版本：**0.7.0**（versionCode 34，开发中，尚未发布）
- 系统要求：**Android 10（API 29）及以上**
- 许可证：[Apache License 2.0](LICENSE)

## 核心功能

- 新建、排序、停用、恢复、导入、重命名和删除分类；重命名可同步修改分类文件夹。
- 分类卡片显示文件夹内直属照片和视频数量，并可跳转到系统文件管理器。
- CameraX 普通拍照：4:3 同范围取景、缩放、闪光、点按对焦和曝光补偿。
- 长按快门录制有声视频：1080p 优先、720p 回退，支持暂停、继续、前后摄切换和同步防抖能力降级。
- 文档模式：实时候选四边形、手动拍摄、四角调整、透视矫正、90°旋转，以及原色／增强／黑白效果。
- 单张待确认文档草稿：返回首页或重启后可继续编辑；重拍安全替换原图，显式丢弃才清理未确认材料。
- Room 持久保存队列：采集、SAF 写入和完整读回校验互不阻塞，进程中断后可恢复可用材料。文档草稿未确认时不会自动归档。
- 媒体直接保存到用户选择的目录；分类删除默认保留已有文件，只有明确勾选后才同步删除目录内容。

## 文档模式与兼容性

普通拍照、独立录像和文档模式的最低系统要求均为 Android 10。文档处理使用本地 OpenCV 4.13.0；分析只在文档取景时开启，最多每秒处理 5 帧。相机组合不支持实时分析或识别失败时，仍可拍摄并手动调整全图四角。

文档默认使用原色，保留颜色但仍进行裁剪与透视矫正；增强采用保守的亮度对比度和锐化，黑白采用灰度效果。确认后只归档一张 JPEG，质量参数为 95，长边不超过 4096 像素且不主动放大。

0.7 完全移除动图采集、设置、缓冲和合成模块；既有动图文件不删除、不改写，仍按 JPEG 图片统计。升级时，可读取的旧动图待保存 JPEG 以普通照片继续恢复。

0.7 文档流程尚待真机验收；不能将历史版本的设备测试结果视为本版已验证的兼容性。历史结果保留在 [版本记录](CHANGELOG.md)。

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
.\gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest --no-daemon
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`；`assembleRelease` 生成未签名 APK，仅检查发布构建，不可直接安装。`assembleDebugAndroidTest` 只编译设备测试，不代表已经运行这些测试。

首次制作正式签名 APK 时，在自己的 PowerShell 终端运行：

```powershell
.\tools\build-release.ps1
```

脚本使用仓库之外的 `Lenshelf/signing/lenshelf-release.p12`（位于当前用户的 LocalAppData）；首次运行且没有密钥时才交互式创建，以后沿用原密钥。输出为 `app/build/outputs/apk/release/Lenshelf-v0.7.0.apk`。密码由终端交互输入，不写入项目或脚本。务必将密钥文件和密码分别安全备份；以后的正式版必须使用同一密钥签名。同一 applicationId 但签名不同的 APK 无法覆盖安装；不要为解决签名冲突直接卸载而丢失应用内分类、授权和待处理材料。

### macOS / Linux

设置 `ANDROID_HOME` 或创建 `local.properties`，然后执行：

```bash
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest --no-daemon
```

GitHub Actions 会在每次推送和 Pull Request 时执行上述电脑端检查，不接触正式密钥，也不自动执行真机测试或发布。CI 产物只用于验证；正式 APK 通过 GitHub Releases 发布，不提交到源码仓库。

## 使用方法

1. 首次启动时选择一个可长期访问的根目录。
2. 新建分类，或从根目录已有的一级文件夹导入分类。
3. 在首页选择分类进入相机，默认“照片”：短按拍照，长按开始有声录像。
4. 切换“文档”后手动拍摄，在确认页调整四角、旋转和效果，再点击“确认保存”。返回首页保留草稿；“重拍”完成前保留原图。
5. 首页可继续编辑或丢弃待确认草稿。有草稿或待保存任务的分类不能重命名、删除；更换当前分类或根目录不会改变既有材料冻结的保存目标。
6. “已提交保存”不等于归档成功；目标文件完成完整读回校验后才算保存成功，失败时保留材料供重试。

## 工程结构

```text
app/src/main/         正式应用源码、清单和资源
app/src/test/         JVM 单元测试
app/src/androidTest/  Room、SAF 和界面仪器测试
docs/                 领域词汇、品牌和隐私说明
tools/                正式签名构建、采集日志和录像音画同步分析脚本
branding/             品牌源素材
```

文档几何模型、OpenCV 图像处理、草稿与保存队列、取景和确认界面分别位于 `domain/`、`document/`、`data/`、`camera/` 和 `ui/`，避免把图像算法塞入界面或存储代码。

## 文档

- [文档索引](docs/README.md)
- [领域模型](docs/DOMAIN_MODEL.md)
- [品牌名称](docs/BRAND-NAME.md)
- [隐私说明](docs/PRIVACY.md)
- [版本记录](CHANGELOG.md)

## 已知限制

- 文档模式不包含 OCR、多页 PDF、图库导入或曲面书页展平，不承诺消除反光和屏幕摩尔纹。
- 候选边框并非识别结果的保证；屏幕、PPT、板书的文字、颜色和细线质量，以及旋转、缩放和镜头坐标映射仍需真机验收。
- OpenCV 的多架构原生库会明显增加通用 APK 体积；当前保留原有 ABI 兼容范围。
- 正式构建和 CI 校验原生库的 16 KiB ZIP 对齐；这不等于已验证 16 KiB 页大小设备的运行兼容性。更多说明见 [Android 官方兼容性指南](https://developer.android.com/guide/practices/page-sizes)。
- 低光、闪光、空间不足、SAF 授权失效、锁屏/来电收束和更多厂商设备仍需专项验证。
- 当前没有应用内图库、逐个媒体删除、RAW/DNG、Ultra HDR、云同步或后台持续录制服务。

## 参与和发布

主仓库由项目维护者管理。外部用户可以 Fork，并通过 Issue 或 Pull Request 提议修改；是否合并和发布由维护者决定。贡献要求见 [CONTRIBUTING](CONTRIBUTING.md)。

正式发布前应确认版本号和 CHANGELOG 一致，完成自动测试、Lint、真机回归、Release 签名和 APK 校验。Release keystore、密码、签名配置和 APK 不进入 Git 历史。

## License

Copyright 2026 Lenshelf project

Licensed under the [Apache License, Version 2.0](LICENSE).
