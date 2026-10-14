// SPDX-License-Identifier: GPL-3.0-only
#include <jni.h>
#include "tone.h"
extern "C" JNIEXPORT void JNICALL
Java_dev_nijiframe_image_NativeTone_apply(JNIEnv* env, jobject, jintArray array, jint count, jfloat ev, jfloat sat) {
    if (!array || count < 0 || count > env->GetArrayLength(array)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid pixel buffer");
        return;
    }
    jint* pixels = env->GetIntArrayElements(array, nullptr);
    if (!pixels) return;
    const int result = nf_tone(reinterpret_cast<uint32_t*>(pixels), static_cast<size_t>(count), ev, sat);
    env->ReleaseIntArrayElements(array, pixels, 0);
    if (result != 0) env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid tone parameters");
}
