<p align="center">
  <img src="docs/images/nijiframe-mark.svg" alt="" width="80" height="80">
</p>

<h1 align="center">NijiFrame</h1>

<p align="center">
  <strong>在本地修图，把原图和调整一起留下。</strong><br>
  从 Android 出发的开源照片编辑器，面向日常照片与单人人像。<br>
  从简单调整开始，也为每一张照片保留继续精调的空间。
</p>

<p align="center">
  <a href="https://github.com/JasonHe17/NijiFrame/actions/workflows/android.yml?query=branch%3Amain"><img src="https://github.com/JasonHe17/NijiFrame/actions/workflows/android.yml/badge.svg?branch=main" alt="Android checks on main"></a>
  <img src="https://img.shields.io/badge/platform-Android-3DDC84" alt="平台：Android">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0--only-7C6BC4" alt="许可证：GPL-3.0-only"></a>
</p>

<p align="center">
  <a href="#features">功能</a> ·
  <a href="#try">试用</a> ·
  <a href="#direction">项目方向</a> ·
  <a href="docs/user-guide.md">使用指南</a> ·
  <a href="docs/development.md">参与开发</a>
</p>

> **开发中 · 0.6.0 周期**：当前已提供 Android 基础编辑与手工局部曝光，尚未发布稳定版。柔肤与可手工修正的人像区域建议为实验候选；Windows 接力仍是后续方向。

## 为什么做 NijiFrame

我们希望修图既容易开始，也能让使用者掌握每一步调整。NijiFrame 从常用的光影、色彩与构图工具做起，逐步探索适合日常人像和写真照片的编辑体验。

- **照片在本机处理。** 当前应用无需账号，无网络权限，编辑过程无需上传照片。
- **原图和调整分开保存。** 可以尝试不同参数、对比原图，或创建独立副本探索另一种风格。导出工程后，下一次还能继续编辑。
- **快速开始，保留精调。** 可以从自然、暖调、冷调、黑白预设出发，也可以逐项控制明暗、颜色和细节。
- **按自己的习惯查看与调节。** 浅色、深色或跟随系统，画布背景独立选择；刻度探索效果，点数值精确输入，完成前可撤销本次调整。
- **源码与工程格式开放。** 自有代码采用 GPL-3.0-only，工程格式有文档，欢迎共同改进功能、体验与实现。

<a id="features"></a>

## 从一张照片，到一个可继续编辑的作品

### 调整光影与色彩

用曝光、对比度、高光、阴影、白色和黑色调整明暗；用饱和度、鲜艳度、色温和色调寻找合适的色彩。锐化与暗角用于细节和氛围调整，预设应用后仍可继续修改参数。

### 找到合适的构图

自由拖动裁剪框，或选择常用比例；旋转、水平／垂直翻转、校正倾斜。编辑时可以缩放查看构图、对比原图，并使用撤销与重做。点击“细节”，可按原尺寸检查所选位置的纹理，比较调整前后；大图也无需先导出整张照片。

### 只调整你选中的区域

用笔刷添加或擦除区域，单独调整局部曝光。区域可以显示、隐藏和撤销；需要定位细节时，切换“移动画布”缩放查看。也可以在“查看细节”的原尺寸窗口中补涂或擦除。区域与曝光参数一起保存在工程中，重新打开后仍可修改。

### 试试手工柔肤候选

在“修饰 → 编辑选区”中涂选皮肤，进入“调整效果”后使用“柔肤”调节强度，并用“细节保护”减少对局部变化的平滑。也可以先获取人像区域建议，检查后再手工修正。柔肤默认关闭；区域建议不是精确皮肤识别，也不改变脸型。当前用于效果试用，尚未完成用户审美验收。操作与限制见[使用指南](docs/user-guide.md)。

### 保存进度，再决定如何分享

已应用的修改会自动保存。主页集中管理多个工程，支持重命名、独立副本和删除。完成后可导出 JPEG／PNG，选择 JPEG 质量与输出尺寸，或通过 Android 系统分享。

想保留后续调整的空间，就另存一份 **`.nijiproj` 工程**：它同时保存原图和编辑参数。照片导出用于展示，工程导出用于继续创作。

<a id="try"></a>

## 试用开发版

当前还没有稳定版安装包。熟悉 Android 开发版安装的使用者可以：

1. 打开 [Android checks](https://github.com/JasonHe17/NijiFrame/actions/workflows/android.yml?query=branch%3Amain)，选择 `main` 分支上成功的运行。
2. 登录 GitHub，在该次运行的 **Artifacts** 中下载 `android-build-<提交号>`，解压取得 `app-debug.apk`。产物保留 7 天，过期后可[从源码构建](docs/development.md)。
3. 安装后导入照片，选择工具并应用调整，再导出照片或工程。完整说明见[使用指南](docs/user-guide.md)，也可按[简短试用步骤](docs/manual-check.md)检查功能。

开发 APK 使用调试签名，尚未针对真实手机完成兼容性与性能验证。卸载或更换安装包签名前，请先导出需要保留的工程。

### 当前支持范围

| 项目 | 当前范围 |
| --- | --- |
| 平台 | Android；配置最低 Android 8.0／API 26，自动化模拟器测试配置覆盖 API 29、31、34、36、37.0 及 API 35 的 16 KiB 页环境 |
| 输入 | JPEG（含常见广色域与 Ultra HDR SDR 底图）、8/16 位不透明 PNG、静态 WebP、系统可解码的 HEIC/HEIF 与 AVIF；单张不超过 2.1 亿像素、64 MiB，单边不超过 32768 像素 |
| 输出 | JPEG／PNG；原尺寸或缩小长边，不放大小图；照片导出不复制原图元数据 |
| 可编辑工程 | 实验性 `.nijiproj` v6，兼容读取 v1–v5；工程包含原图及原有元数据 |
| 保存 | 已应用的修改自动保存；未应用预览和跨次打开的完整撤销历史不属于持久保存内容 |

照片按 **sRGB／SDR** 编辑与导出，原始文件完整保留；广色域／HDR 输入不代表已支持对应的编辑与输出。RAW、TIFF、独立 HLG/PQ HDR、透明图片、图层合成和批量处理尚未提供。编码与设备限制见[照片格式与色彩](docs/photo-input.md)。大图使用缩略预览并保留原图；原尺寸导出取决于可用内存，必要时可选择较小尺寸。输入上限不是所有设备的性能承诺，详见[大图处理](docs/large-images.md)。

<a id="direction"></a>

## 项目方向

**现在：做好基础编辑。** 打磨从导入、调整、保存到重开和导出的体验，收集真实设备上的问题与反馈。

**接下来：探索自然的人像调整。** 围绕单人日常照片与写真，在手工区域基础上研究细节保留和可精调的人像工具；效果与可用性仍需验证。

**更远：让可编辑工程跨设备流转。** 考虑 Android 与 Windows 之间继续编辑的工作流。当前已分离平台界面、编辑状态和处理核心，但尚无 Windows 客户端。

这些是发展方向，具体范围会随验证结果调整；当前操作见[使用指南](docs/user-guide.md)，检查项目与已知问题见[验证范围](docs/validation.md)。

## 一起改进

欢迎提交可复现的问题、使用体验和改进建议。反馈问题时请附应用版本、Android 版本与操作步骤；只有在愿意公开且具备相应授权时才附示例图片。

- [报告问题与提出建议](https://github.com/JasonHe17/NijiFrame/issues)
- [构建与开发](docs/development.md) · [贡献说明](CONTRIBUTING.md)
- [架构](docs/architecture.md) · [工程格式](docs/project-format.md) · [验证范围](docs/validation.md)
- [手机兼容调查与测试范围](docs/device-compatibility.md)

## 许可

NijiFrame 自有代码采用 [GPL-3.0-only](LICENSE)。第三方组件保留各自许可，见[第三方声明](THIRD_PARTY_NOTICES.md)。项目不授予无权许可的第三方专利权。
