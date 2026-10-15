# Third-party components

NijiFrame's own code is GPL-3.0-only. Third-party components retain their own terms.
No portrait models, reference-app source, external photographs or research sample assets are included.

| Component | Selected version | License / primary source |
| --- | --- | --- |
| AndroidX Compose, Activity, Lifecycle, ExifInterface and their AndroidX dependencies | See `gradle/libs.versions.toml` and `docs/runtime-dependencies.txt` | Apache-2.0; [AndroidX source](https://android.googlesource.com/platform/frameworks/support/), individual artifact POMs and bundled notices |
| Kotlin standard library | 2.2.21 | Apache-2.0; [JetBrains Kotlin](https://github.com/JetBrains/kotlin/tree/v2.2.21/license) |
| kotlinx.coroutines | 1.8.1 | Apache-2.0; [source license](https://github.com/Kotlin/kotlinx.coroutines/blob/1.8.1/LICENSE.txt) |
| kotlinx.serialization | 1.9.0 | Apache-2.0; [source license](https://github.com/Kotlin/kotlinx.serialization/blob/v1.9.0/LICENSE.txt) |
| JetBrains annotations | 23.0.0 | Apache-2.0; [source](https://github.com/JetBrains/java-annotations) |
| Guava ListenableFuture | 9999.0-empty-to-avoid-conflict-with-guava (with Guava below) | Apache-2.0; [source](https://github.com/google/guava) |
| JSpecify annotations | 1.0.0 | Apache-2.0; [source license](https://github.com/jspecify/jspecify/blob/v1.0.0/LICENSE) |
| Gradle wrapper | 8.13 | Apache-2.0; [source license](https://github.com/gradle/gradle/blob/v8.13.0/LICENSE); generated scripts retain upstream headers |
| NDK C++ runtime support | NDK 28.2.13676358 | Apache-2.0 WITH LLVM-exception; license excerpt retained from the selected NDK's `NOTICE.toolchain` |
| JUnit, AndroidX Test core/runner and UIAutomator (test APK only) | 4.13.2 / 1.7.0 (resolved core) / 1.6.2 / 2.4.0 | EPL-1.0 / Apache-2.0; [JUnit license](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt), [AndroidX Test](https://github.com/android/android-test) |

Apache and LLVM license texts are in `licenses/`. The upstream Gradle distribution license is retained separately as `Gradle-LICENSE.txt`; its broader inventory does not describe the app runtime. The complete selected NDK toolchain notice is also retained there as a superset: it includes build tools and is not a claim that every listed component is linked into the app. Existing dependency notices inside AARs/JARs remain packaged. The app also packages the project license, this inventory and these license texts under `assets/open-source/`.

The native tone and experimental bilateral softening implementations were written for this project from the standard sRGB transfer function, explicit linear-light luminance coefficients and spatial/range weighted averaging, without copying a competitor's implementation. This inventory is dependency provenance, not a third-party patent clearance opinion. Reassess actual algorithms and interactions when portrait processing or a distinctive new interface is introduced.

Before publishing binaries, regenerate the resolved dependency inventory, inspect all packaged libraries and notices, and provide corresponding source and build instructions for the exact release. The development APK is not a signed production release.

## Portrait region inference dependencies

Added runtime dependencies, resolved for the 0.6.0 development cycle:

| Component | Version | License and primary source |
| --- | --- | --- |
| MediaPipe Tasks Vision / Core | 1.1.0 | Apache-2.0; [Google Maven](https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-core/1.1.0/tasks-core-1.1.0.pom), [upstream source](https://github.com/google-ai-edge/mediapipe) |
| Face Landmarker bundle (BlazeFace / Face Mesh V2 / Blendshape V2) | float16 `/1/`, SHA-256 `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff` | Apache-2.0 per each model card; Google / MediaPipe Authors; unmodified weights; [provenance and limitations](models/README.md) |
| Guava / failureaccess | 27.0.1-android / 1.0.1 | Apache-2.0; [Guava](https://github.com/google/guava/tree/v27.0.1); retained `Guava-COPYING.txt` |
| Flogger / system backend | 0.6 | Apache-2.0; [upstream](https://github.com/google/flogger) |
| Protocol Buffers Java Lite | 4.26.1 | BSD-3-Clause; [v26.1 source license](https://github.com/protocolbuffers/protobuf/blob/v26.1/LICENSE); retained `protobuf-LICENSE.txt` |
| Google Data Transport API / backend CCT / runtime | 3.0.0 / 3.1.0 / 3.1.0 | Apache-2.0; [Firebase Android SDK source](https://github.com/firebase/firebase-android-sdk) |
| Firebase encoders / encoders proto / encoders JSON | 17.0.0 / 16.0.0 / 18.0.0 | Apache-2.0; [upstream license](https://github.com/firebase/firebase-android-sdk/blob/master/LICENSE) |
| JSR305 / Error Prone annotations / J2ObjC annotations | 3.0.2 / 2.2.0 / 1.1 | Apache-2.0; [JSR305 source](https://github.com/findbugsproject/findbugs), [Error Prone](https://github.com/google/error-prone), [J2ObjC](https://github.com/google/j2objc) |
| Checker compatibility qualifiers | 2.5.3 | MIT for annotations; [selected-version license](https://github.com/typetools/checker-framework/blob/checker-framework-2.5.3/LICENSE.txt), retained `Checker-Framework-LICENSE.txt` explaining the scope (other framework parts have different licenses) |

MediaPipe's two AAR `META-INF/NOTICE` files are copied verbatim into `assets/open-source/MediaPipe-*-NOTICE.txt` in the APK without removing the upstream privacy notice or bundled native-library attributions. Those upstream notices are a broad inventory, not a claim that every listed build tool is used by NijiFrame. The optional LiteRT-LM Maven dependency is not resolved or bundled by this app.

MediaPipe 1.1.0 contains metrics logging/transport code. NijiFrame removes its network permissions, backend-discovery service and upload scheduler components in the merged manifest; it provides offline, explicitly requested inference. See [implementation](docs/portrait-regions.md). Recheck these assumptions before changing the runtime or adding network access. A permissive runtime/model license does not establish patent clearance; current claim comparisons are documented separately.
