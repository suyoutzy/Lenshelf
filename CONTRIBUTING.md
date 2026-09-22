# 参与 Lenshelf

Lenshelf 的主仓库由项目维护者管理。公开仓库的读取权限不会赋予任何人直接修改主分支、合并提交或发布版本的权限。

## 反馈问题

提交 Issue 前请先搜索已有记录，并提供：

- Lenshelf 版本、手机型号和 Android 版本；
- 前后摄、倍率、Live、闪光和录像状态；
- 可复现步骤、实际结果与预期结果；
- 已去除私人照片、目录名和设备标识的日志。

不要上传私人媒体、SAF 目录 URI、数据库、签名文件或密钥。

## 代码修改

外部修改应在个人 Fork 中完成，并通过 Pull Request 提议。是否合并由项目维护者决定；未经授权不能直接写入主仓库。提交前至少执行：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

相机能力不能只依据 API 请求或编译成功判断。涉及分辨率、帧率、Live、音画同步、方向和防抖的变更，应附最终 JPEG/MP4 元数据或真机验证结果。

提交贡献即表示该贡献按仓库的 Apache License 2.0 授权。
