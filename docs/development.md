# 构建与开发

从[项目首页](../README.md)了解项目定位；提交约定见[贡献说明](../CONTRIBUTING.md)。

## 获取源码

```sh
git clone https://github.com/JasonHe17/NijiFrame.git
cd NijiFrame
```

## 环境与检查

需要 JDK 21、Android SDK API 36、Build Tools 36.0.0、NDK 28.2.13676358、CMake 3.22.1。设置 `ANDROID_HOME` 或未提交的 `local.properties`；不依赖仓库外脚本。

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
./gradlew :editor-core:test :app:assembleDebug :app:lintDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`，包含 arm64-v8a 和 x86_64。配置最低 API 26，实际验证范围单独记录，不以配置代替设备验证。

使用专用 API 29+ 测试模拟器运行包含真实系统选择器和触控流程的 Android 检查。运行器会重装／卸载测试应用，需要保留的工程请先导出：

```sh
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:installDebug
```

Linux 上检查独立 C++ 核心（启用 ASan/UBSan）：

```sh
cmake -S image-core/src/main/cpp -B build/native -DCMAKE_BUILD_TYPE=Debug
cmake --build build/native
ctest --test-dir build/native --output-on-failure
```

[GitHub Actions](https://github.com/JasonHe17/NijiFrame/actions/workflows/android.yml) 执行构建、Lint、JVM/native 检查和 API 29／36 模拟器测试，保留报告与开发 APK。应查看具体提交的运行结果；历史失败不会被描述为通过。物理手机与 Windows 尚未验证。


## 模块导航

| 模块 | 职责 |
| --- | --- |
| `app` | Compose 界面、Android 文件访问、工程列表和图片编解码 |
| `editor-core` | 不依赖 Android 类型的编辑状态、几何规则和工程读写 |
| `image-core` | C++17 CPU 图像处理参考实现与 JNI 接口 |

当前边界为后续平台扩展保留空间，不代表已有 Windows 客户端。进一步阅读[架构](architecture.md)、[工程格式](project-format.md)和[验证范围](validation.md)。

For versioned alpha distribution and signing preparation, see [Release preparation](releases.md).
