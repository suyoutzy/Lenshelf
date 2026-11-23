# 参与 Lenshelf

欢迎通过 Issue 反馈问题和建议，或通过 Pull Request 参与改进。项目维护者负责评审、合并与发布。

## 反馈问题

提交 Issue 前请先搜索已有记录，并提供：

- Lenshelf 版本、手机型号和 Android 版本；
- 前后摄、倍率、照片／文档模式、闪光和录像状态；
- 可复现步骤、实际结果与预期结果；
- 已去除私人照片、目录名和设备标识的日志。

不要上传私人媒体、SAF 目录 URI、数据库、签名文件或密钥。

## 代码修改

请在个人 Fork 中完成修改，再提交 Pull Request，说明变更目的和验证结果。环境与操作见[构建与开发](docs/BUILD.md)。文档修改检查文字、链接及其与当前行为的一致性即可；应用代码变更提交前至少执行：

```bash
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest --no-daemon
```

相机能力不能只依据 API 请求或编译成功判断。涉及分辨率、帧率、文档四角与成片质量、音画同步、方向和防抖的变更，应附最终 JPEG/MP4 元数据或真机验证结果；设备测试仅编译时必须明确标注“未执行”。

提交贡献即表示该贡献按仓库的 Apache License 2.0 授权。
