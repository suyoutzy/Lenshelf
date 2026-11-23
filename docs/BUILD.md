# Lenshelf 构建与开发

应用使用方法见[使用指南](USAGE.md)，正式安装包见[GitHub Releases](https://github.com/suyoutzy/Lenshelf/releases/latest)。

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

首次制作正式签名 APK 时，在自己的 PowerShell 终端运行。脚本优先使用项目中的 `.android-sdk`；使用外部 SDK 时，先在同一终端设置 `$env:ANDROID_HOME = 'C:\Android\Sdk'`，指向实际 SDK 目录：

```powershell
.\tools\build-release.ps1
```

脚本使用仓库之外的 `Lenshelf/signing/lenshelf-release.p12`（位于当前用户的 LocalAppData）；首次运行且没有密钥时才交互式创建，以后沿用原密钥。输出按版本号命名，如 `app/build/outputs/apk/release/Lenshelf-v0.9.1.apk`。密码由终端交互输入，不写入项目或脚本。务必将密钥文件和密码分别安全备份；以后的正式版必须使用同一密钥签名。同一 applicationId 但签名不同的 APK 无法覆盖安装；不要为解决签名冲突直接卸载而丢失应用内分类、授权和待处理材料。

### macOS / Linux

设置 `ANDROID_HOME` 或创建 `local.properties`；首次检出后执行 `chmod +x gradlew`，然后执行：

```bash
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest --no-daemon
```

设备测试需先在测试机授权本地根目录。首页与分类目录实写检查使用 `allowHomeDirectoryFixture=true`，实拍使用 `allowDeviceCapture=true`，文件管理器跳转使用 `allowDirectoryOpen=true`；这些验收用例使用新建的独立测试目录。

GitHub Actions 会在每次推送和 Pull Request 时执行上述电脑端检查，不接触正式密钥，也不自动执行真机测试或发布。CI 产物只用于验证；正式 APK 通过 GitHub Releases 发布，不提交到源码仓库。

## 工程结构

```text
app/src/main/         正式应用源码、清单和资源
app/src/test/         JVM 单元测试
app/src/androidTest/  Room、SAF 和界面仪器测试
docs/                 领域词汇、品牌和隐私说明
tools/                正式签名构建、采集日志和录像音画同步分析脚本
branding/             品牌源素材
```

文档几何模型、OpenCV 图像处理、持久材料与保存队列、相机采集和快速预览分别位于 `domain/`、`document/`、`data/`、`camera/` 和 `ui/`，避免把图像算法塞入界面或存储代码。

## 发布

正式发布前确认版本号和 CHANGELOG 一致，完成对应变更的测试、Lint、构建、真机回归、Release 签名和 APK 校验。签名密钥、密码、签名配置及 APK 不进入 Git 历史。文档修改只需核对文字、链接与当前行为，无需重复相机测试。

## 兼容性与验证边界

录像和同步防抖取决于镜头能力及实际绑定结果；API 请求或构建成功不能替代成片验证。分辨率、帧率、方向和音画同步等变更，应检查最终 JPEG／MP4 及实际视野。

通用 APK 包含 OpenCV 的多架构原生库，体积较大。正式构建和 CI 校验原生库的 16 KiB ZIP 对齐，这不等于已验证 16 KiB 页大小设备的运行兼容性，见[Android 官方兼容性指南](https://developer.android.com/guide/practices/page-sizes)。

历史版本的验证结果和未完成项见[版本记录](../CHANGELOG.md)，不能将旧版结果视为后续所有变更均已通过同样检查。
