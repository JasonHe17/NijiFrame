# 手机照片与设备兼容调查

调研日期：2026-10-15。本文是测试范围与工程决策，不是已认证机型清单。厂商规格只证明其声明的功能；地区、固件、相机模式、分享方式可能改变实际文件。未取得原文件的项目均不能记为文件实测。

## 选择范围

先覆盖中国大陆常见品牌，再补充全球 Android 和跨设备传入的照片。[Omdia 2026 年第二季度报告](https://omdia.tech.informa.com/pr/2026/july/mainland-china-smartphone-market-shipments-decline-2percent-in-2q26-huawei-and-apple-buck-the-trend)列出华为、Apple、OPPO、vivo、小米等主要出货厂商；该报告的 OPPO 口径包含一加和 realme。出货份额不等于存量设备、项目用户比例，也不意味着这些设备都能运行 Android APK。荣耀及中端高像素设备仍单独纳入。

以下机型是代表性样本，不是完整销量排行，也不要求购买全部机型。运行应用的设备与拍摄照片的设备分开登记。

| 来源／代表机型 | 官方证据 | 对本项目的含义与尚缺证据 |
| --- | --- | --- |
| 小米 15 Ultra；17 Ultra | [15 Ultra 英国规格](https://www.mi.com/uk/product/xiaomi-15-ultra/specs/)列出 50/200 MP 模式、JPEG/HEIF/DNG、UltraRAW；[17 Ultra 全球规格](https://www.mi.com/global/product/xiaomi-17-ultra/specs/)列出 JPG/HEIF、200 MP 模式及 RAW 模式 | 优先验证大尺寸 JPEG、广色域、SDR 底图 HDR、HEIF。不能把全球规格等同国行固件；不能从“HDR”字样推断具体增益图结构 |
| Redmi Note 14 Pro 5G 全球版 | [全球产品页](https://www.mi.com/global/product/redmi-note-14-pro-5g/)列出 200 MP 主摄 | 高像素也出现在中端产品，不能按相机像素推定可用内存。该页面不证明每种模式的输出尺寸或文件编码，国行同名型号也不能直接套用 |
| vivo X200 Pro／X300 Pro | [X200 Pro 全球页](https://www.vivo.com/en/products/x200-pro)列出 200 MP 长焦；[X300 Pro 国行参数](https://www.vivo.com.cn/vivo/param/x300pro)作为新一代规格核查入口 | 纳入天玑设备、原始尺寸、方向与分享链路。X300 Pro 的文件编码、位深、ICC、增益图具体组合仍待原文件验证，不从显示屏色域推导照片色域 |
| OPPO Find X9 Pro；一加／realme 后续样本 | [国行规格](https://www.oppo.com/cn/smartphones/series-find-x/find-x9-pro/specs/)列出 JPG（默认）、HEIF、RAW | JPG 与 HEIF 分组验证；不能把 OPPO 单一机型的文件结果推广到一加、realme 或全部 ColorOS 版本 |
| 荣耀 400／Magic 系列候选 | [荣耀 400 国行页](https://www.honor.com/cn/phones/honor-400/)列出 2 亿像素摄像头，并注明实际照片像素随模式变化 | 补充中端／人像定位设备；尚无文件格式和真机实测，不把传感器像素当作每张照片尺寸 |
| 华为 Mate／Pura 系列 | [适用 Mate 80 等设备的相机设置](https://consumer.huawei.com/cn/support/content/zh-cn16075822/)说明 JPG/HEIF 选项；[系统升级应用说明](https://consumer.huawei.com/cn/support/content/zh-cn16027303/)要求检查升级后的应用支持情况 | 接收其传出的照片。原生 HarmonyOS 客户端作为独立平台议题；当前不承诺 APK 在 HarmonyOS 5+、兼容容器或任何华为机型上可用。旧 EMUI/HarmonyOS 也须按实际系统验证 |
| Samsung Galaxy S23／S25／S26 Ultra | [S23 相机说明](https://www.samsung.com/us/support/answer/ANS10003680/)明确 HEIF 高效照片选项；[S26 Ultra 英国页](https://www.samsung.com/uk/smartphones/galaxy-s26-ultra/)列出 200 MP 主摄 | 全球 Android 补充 JPEG/HEIF、大尺寸、One UI 文件选择器；旧机型格式说明不当作 S26 各模式的实测证据 |
| Pixel 与 Google Photos 链路 | [Google Photos HDR 说明](https://support.google.com/photos/answer/14159275?co=GENIE.Platform%3DAndroid&hl=en-GB)说明 Ultra HDR 与 SDR 显示退路 | 验证 SDR 底图、增益图保留及图库导出后的变化，不以 AOSP 模拟器代替 Pixel 真机 |
| iPhone 传入的照片 | [Apple HEIF 说明](https://support.apple.com/en-ie/116944)说明高效格式与部分分享链路自动转换；[ProRAW 说明](https://support.apple.com/en-ie/119916)说明 DNG 与 12/48 MP 模式 | HEIF 原件和分享后 JPEG 应分别登记。ProRAW 不在当前支持范围；照片输入兼容不代表已有 iOS 客户端 |

## 从证据到实现

### 本轮落实

- **200 MP 级静态图输入：**资源像素上限由 100 MP 提高至 210 MP，包含 16384×12288（201,326,592 像素）这样的测试尺寸。它是合成边界样本，不声称来自上述某款手机。保留 64 MiB 编码字节上限、32768 单边上限、采样预览和独立导出内存检查。
- **原件与细节：**约 201 MP 合成 JPEG 通过实际导入、EXIF 旋转、原尺寸纹理、编辑、工程重开、缩小导出及内存拒绝路径验证；不在 Android 测试中构建整张 201 MP 位图。既有 12/48/100 MP 回归保留。
- **系统边界：**CI 配置从 API 29/36 扩展至 29/31/34/36，分别覆盖旧 HEIF 行为、Android 12 AVIF、Android 14 gainmap API 和 Android 16 路径。必须以对应提交的 CI 结果判断执行是否成功。
- **反馈入口：**提供兼容性问题模板，要求应用／系统版本、操作和错误，图片信息可选。不要求公开照片、EXIF GPS、设备序列号或完整系统日志。

此次没有新增运行时编解码依赖、修改色彩算法或按品牌添加特殊判断。格式策略继续遵循[照片输入文档](photo-input.md)：8 位 sRGB/SDR 编辑输出、保留原始编码文件、明确拒绝尚不能可靠处理的内容。HEIF/AVIF 的整帧内部缓冲可能导致内存拒绝，不能套用 JPEG 的低内存结论。

### 测试矩阵与验收

| 维度 | 当前检查／新增检查 | 验收与未覆盖项 |
| --- | --- | --- |
| 格式／颜色 | sRGB、P3、Adobe RGB JPEG；SDR 底图 Ultra HDR；PNG 8/16；静态 WebP；SDR HEIF/AVIF | 预览、细节、导出颜色一致；原字节一致；不支持时有错误。10 位 HEIF/AVIF、厂商增益图变体仍待样本 |
| 分辨率／字节数 | 12、48、100、201 MP；210 MP 边界；>32 MiB 编码输入 | 保留原坐标和原件；小尺寸输出符合选择；不静默降低原尺寸输出；>64 MiB 仍拒绝 |
| 内存 | 注入受限预算，原尺寸导出拒绝且已有目标不被截断 | 预算测试不是低内存手机实测；峰值内存、解码器临时缓冲、发热待真机 |
| 操作系统 | CI API 29/31/34/36 | API 26 配置最低版本仍未完整验证；Android 17/API 37 待可用镜像接入。中间版本不能仅凭端点通过写成已测试 |
| 芯片／ABI | x86_64 模拟器执行，arm64 编译 | 骁龙、天玑及不同 GPU 尚无代表性真机执行证据。以后启用 GPU 后端须重新验证驱动兼容 |
| 文件来源 | 系统选择器、URI 读写、权限失败与重开回归 | 厂商图库／云图库／聊天软件保存／相机导出分别采样；传输可能转码或移除元数据 |
| 屏幕／生命周期 | 竖横屏、短屏、活动重建和草稿回归 | 折叠屏、多窗口、字体放大、后台进程回收与存储不足列入手工候选；不宣称全面完成 |

[Android 格式文档](https://developer.android.com/media/platform/supported-formats)用于确定 API 与解码能力边界；厂商设备上仍以实际文件与解码结果为准。[Android 17 文档](https://developer.android.com/about/versions/17)需要随 SDK 镜像可用性补充验证。模拟器品牌外观配置不等于对应品牌固件。

## 下一批验证与优先级

1. **P0：日常 JPG 闭环。**优先收集自愿提供或明确许可的普通拍摄、广色域／HDR、高像素照片。普通真机试用只做导入→轻量调整→保存→重开→缩小导出；已遇到失败的原文件单独保留，不以重新拍摄的成功样本替代。
2. **P1：扩展设备与格式。**补充一台天玑设备、一台较低内存 Android、一个 One UI 来源或贡献者结果；按可获得条件选择，不默认采购。比较同一文件跨设备的颜色和方向。补 API 26 与当前最新系统，验证 10 位 SDR HEIF、动态照片静态主图和多图辅助项。
3. **P1：大字节文件与导出。**>64 MiB 的真实照片先统计大小／格式／处理路径，随后评估流式资源存储和分块输出；不要直接扩大字节上限导致多个完整 byte array 同时驻留。原尺寸导出内存不足仍是当前限制。
4. **P2：完整 HDR、RAW 和跨端。**另行确定色彩合同、依赖许可、专利及真机显示评价。当前只编辑动态照片的可解码静态主图，不承诺动态播放、景深重编辑或辅助帧编辑；原始容器如被接收则完整保留。

每份样本记录：来源与许可、获取日期、拍摄设备／相机模式（已知时）、运行设备／Android 与厂商系统版本、传输方式、文件哈希、实际尺寸／字节数／编码／ICC／方向／HDR 标记，以及失败阶段。未知字段明确写未知。公开 CI 只使用合成或允许公开的素材；私人照片及派生数据只在本地。

证据状态分别记为“官方声明”“原文件已检查”“模拟器链路通过”“指定真机链路通过”。不得把品牌矩阵的条目数量当作兼容率，也不从照片 EXIF 的 Make/Model 决定色彩修正。

## 依赖与专利边界

本轮沿用平台解码、采样预览和既有区域渲染，仅扩大资源边界与测试覆盖；没有新增增益图重建、显示器自适应色调映射或厂商算法复刻。已有权利要求比对与未决事项见[照片输入专利说明](photo-input.md#patent-and-dependency-notes)。更大图片的支持不构成新的专利清障结论；后续自行捆绑 HEVC/RAW 编解码器或采用 HDR 算法时需重新调查。
