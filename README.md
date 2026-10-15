# NijiFrame

NijiFrame 是本地照片编辑开源项目，长期面向单人人像，先实现 Android 基础修图。当前开发版为 **0.2.0-dev**；Windows 接力保留工程与处理边界，人像特色工具仍属后续范围。

## 基础功能

| 区域 | 已实现 |
| --- | --- |
| 工程主页 | 导入照片、打开可编辑工程、缩略图列表、重命名、创建独立副本、确认删除、恢复最近工程 |
| 画布与交互 | 缩放、平移、双击缩放、适应画布、保留构图的原图对比、工具预览／应用／取消、撤销重做、横竖屏布局 |
| 明暗与颜色 | 曝光、对比度、高光、阴影、白色、黑色、饱和度、鲜艳度、色温、色调 |
| 构图 | 拖动自由裁剪、常用比例、移动裁剪框、90°旋转、水平／垂直翻转、角度校正 |
| 细节与预设 | 锐化、暗角，以及原色、自然、暖调、冷调、黑白预设；参数可继续精调 |
| 保存与分享 | 已应用修改自动保存、JPEG／PNG导出、JPEG质量与长边设置、系统分享、独立的`.nijiproj`工程导出 |

照片在本机处理，无账号、网络权限或模型下载。自动磨皮、局部修复／蒙版、液化、生成式处理、图层合成、批量和 RAW/HDR 工作流不在当前基础版中。

## 使用与数据保护

1. 导入照片或打开工程，选择工具查看实时预览。点“应用调整”或切换工具会应用当前工具；“取消调整”还原本次工具中的修改。
2. 已应用的修改自动保存；请留意“调整待应用”“自动保存中”“已保存”状态。未应用的工具预览不属于自动保存内容，进程结束后不保证恢复。重置全部调整可撤销；跨工程重开不保留完整撤销历史。
3. 从“项目”回到主页管理多个作品。工程保存完整原图副本和参数，移动外部原图后仍可编辑。删除工程不删除外部原图；卸载应用会删除内部工程，请提前导出重要作品。
4. “导出”可选择照片格式、质量、原尺寸或缩小长边；不会放大小图。导出照片不复制 GPS 等原图元数据。导出工程包含原图及其原始元数据，适合备份和继续编辑。

当前输入范围为 sRGB JPEG 和 8 位不透明 PNG，上限 800 万像素、32 MiB。透明、宽色域、已识别的 HDR 增益图及其他不支持的输入会明确提示。此限制尚未经真机性能验证。工程格式仍为实验版本；可读取并迁移基础版 v1 工程，新保存使用 v2。

## 构建与检查

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

## 开发资料与许可

- [基础版范围与验收清单](docs/basic-editor-plan.md)
- [架构](docs/architecture.md)、[v2工程语义与v1迁移](docs/project-format.md)
- [验证方法与覆盖范围](docs/validation.md)、[贡献说明](CONTRIBUTING.md)
- [第三方来源与许可](THIRD_PARTY_NOTICES.md)

项目自有代码采用 **GPL-3.0-only**，见 [LICENSE](LICENSE)。第三方组件保留各自许可；引入算法、模型和素材时分别核查来源、许可及相关专利问题。项目不授予无权许可的第三方专利权。
