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
| Guava ListenableFuture | 1.0 | Apache-2.0; [source](https://github.com/google/guava) |
| JSpecify annotations | 1.0.0 | Apache-2.0; [source license](https://github.com/jspecify/jspecify/blob/v1.0.0/LICENSE) |
| Gradle wrapper | 8.13 | Apache-2.0; [source license](https://github.com/gradle/gradle/blob/v8.13.0/LICENSE); generated scripts retain upstream headers |
| NDK C++ runtime support | NDK 28.2.13676358 | Apache-2.0 WITH LLVM-exception; license excerpt retained from the selected NDK's `NOTICE.toolchain` |
| JUnit, AndroidX Test core/runner and UIAutomator (test APK only) | 4.13.2 / 1.6.1 / 1.6.2 / 2.4.0 | EPL-1.0 / Apache-2.0; [JUnit license](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt), [AndroidX Test](https://github.com/android/android-test) |

Apache and LLVM license texts are in `licenses/`. The upstream Gradle distribution license is retained separately as `Gradle-LICENSE.txt`; its broader inventory does not describe the app runtime. The complete selected NDK toolchain notice is also retained there as a superset: it includes build tools and is not a claim that every listed component is linked into the app. Existing dependency notices inside AARs/JARs remain packaged. The app also packages the project license, this inventory and these license texts under `assets/open-source/`.

The native tone implementation was written for this project from the standard sRGB transfer function and explicit linear-light luminance coefficients, without copying a competitor's implementation. This inventory is dependency provenance, not a third-party patent clearance opinion. Reassess actual algorithms and interactions when portrait processing or a distinctive new interface is introduced.

Before publishing binaries, regenerate the resolved dependency inventory, inspect all packaged libraries and notices, and provide corresponding source and build instructions for the exact release. The development APK is not a signed production release.
