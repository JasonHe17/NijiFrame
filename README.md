# NijiFrame

NijiFrame 是面向单人人像的本地修图开源项目，Android 先行，为后续 Windows 可编辑工程接力保留数据和像素处理边界。

当前已有可构建的基础编辑器：导入照片、缩放平移、曝光与饱和度、居中方形裁剪、90° 旋转、原图对比、撤销重做、保存／重开工程和导出照片。自然皮肤改善、局部蒙版及 Windows 客户端尚未实现。

## 使用范围

- 支持 sRGB JPEG、8 位不透明 PNG；先应用 EXIF 方向，再编辑。当前临时限制为 800 万像素、32 MiB 原图，不代表已验证的真机能力。
- 照片处理在本地运行，无账号、网络权限或模型下载。透明图、宽色域与已识别的 HDR 增益图会明确拒绝。
- **保存工程**将当前原图和参数写入应用内的一个恢复槽；下次启动会恢复最后一次成功保存。未保存的修改在进程结束后不保证恢复。保存新工程会替换该恢复槽，重要作品请导出工程备份。
- **导出工程**生成 `.nijiproj` 文件，包含原图及其原始元数据，可通过“打开工程”继续修改。即使原始外部照片被移走，工程仍能打开。卸载应用会删除内部恢复槽。
- **导出照片**生成裁剪／旋转后完整尺寸的 sRGB JPEG，质量 95，不复制 GPS 等原图元数据。照片导出与工程保存分开；导出前也会保存内部恢复副本。
- 当前裁剪只有居中方形开关；首版格式仍为实验版本。Android 与 Windows 的实际接力尚未验证。

## 构建与验证

需要 JDK 21、Android SDK（API 36、Build Tools 36.0.0）、NDK 28.2.13676358、CMake 3.22.1。设置 `ANDROID_HOME` 或未提交的 `local.properties`；无需任何仓库外脚本。

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
./gradlew :editor-core:test :app:assembleDebug :app:lintDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。当前构建包含 `arm64-v8a`、`x86_64`，最低 API 26 是配置起点；实际验证环境见 [验证说明](docs/validation.md)。依赖和工具版本固定于仓库，版本选择不是“最新版本”承诺。

启动一个 Android 模拟器或连接设备后：

```sh
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:installDebug
```

Linux 主机上验证独立像素核心：

```sh
cmake -S image-core/src/main/cpp -B build/native -DCMAKE_BUILD_TYPE=Debug
cmake --build build/native
ctest --test-dir build/native --output-on-failure
```

GitHub Actions 配置执行 JVM 测试、APK 构建、Lint、native sanitizer 检查和 API 36 模拟器测试。配置落地不等于已在 GitHub 上运行；本地验证记录单独列出。

## 开发资料

- [架构与实施顺序](docs/architecture.md)
- [工程格式 v1](docs/project-format.md)
- [验证范围与手工步骤](docs/validation.md)
- [贡献说明](CONTRIBUTING.md)
- [第三方来源与许可](THIRD_PARTY_NOTICES.md)

项目自有代码采用 **GPL-3.0-only**，见 [LICENSE](LICENSE)。第三方代码、模型和素材须分别核查许可及必要声明；项目不授予无权许可的第三方专利权。多人、批量、复杂合成及自动人像效果属于后续范围。
