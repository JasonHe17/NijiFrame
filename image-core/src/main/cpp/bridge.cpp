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


static void invalid(JNIEnv* env) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid pixel operation");
}
extern "C" JNIEXPORT void JNICALL
Java_dev_nijiframe_image_NativeTone_applyBasic(JNIEnv* env, jobject, jintArray array, jint count, jfloatArray params) {
    if (!array || !params || count < 0 || count > env->GetArrayLength(array) || env->GetArrayLength(params) != 10) { invalid(env); return; }
    jfloat values[10];
    env->GetFloatArrayRegion(params, 0, 10, values);
    if (env->ExceptionCheck()) return;
    nf_adjustments p{values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9]};
    jint* pixels = env->GetIntArrayElements(array, nullptr);
    if (!pixels) return;
    const int result = nf_basic_tone(reinterpret_cast<uint32_t*>(pixels), static_cast<size_t>(count), &p);
    env->ReleaseIntArrayElements(array, pixels, 0);
    if (result != 0) invalid(env);
}
extern "C" JNIEXPORT void JNICALL
Java_dev_nijiframe_image_NativeTone_sharpen(JNIEnv* env, jobject, jintArray source, jintArray destination, jint width, jint height, jfloat amount) {
    const int64_t size = static_cast<int64_t>(width)*height;
    if (!source || !destination || env->IsSameObject(source, destination) || width <= 0 || height <= 0 ||
        size > env->GetArrayLength(source) || size > env->GetArrayLength(destination)) { invalid(env); return; }
    jint* input = env->GetIntArrayElements(source, nullptr);
    if (!input) return;
    jint* output = env->GetIntArrayElements(destination, nullptr);
    if (!output) { env->ReleaseIntArrayElements(source, input, JNI_ABORT); return; }
    const int result = nf_sharpen(reinterpret_cast<uint32_t*>(input), reinterpret_cast<uint32_t*>(output), width, height, amount);
    env->ReleaseIntArrayElements(source, input, JNI_ABORT);
    env->ReleaseIntArrayElements(destination, output, 0);
    if (result != 0) invalid(env);
}
extern "C" JNIEXPORT void JNICALL
Java_dev_nijiframe_image_NativeTone_vignette(JNIEnv* env, jobject, jintArray array, jint width, jint full_height, jint first_row, jint rows, jfloat amount) {
    if (!array || width <= 0 || rows <= 0 || static_cast<int64_t>(width)*rows > env->GetArrayLength(array)) { invalid(env); return; }
    jint* pixels = env->GetIntArrayElements(array, nullptr);
    if (!pixels) return;
    const int result = nf_vignette(reinterpret_cast<uint32_t*>(pixels), width, full_height, first_row, rows, amount);
    env->ReleaseIntArrayElements(array, pixels, 0);
    if (result != 0) invalid(env);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nijiframe_image_NativeTone_localExposure(JNIEnv* env, jobject, jintArray array, jbyteArray mask, jint count, jfloat ev) {
    if (!array || !mask || count<0 || count>env->GetArrayLength(array) || count>env->GetArrayLength(mask)) { invalid(env); return; }
    jint* pixels=env->GetIntArrayElements(array,nullptr);
    if (!pixels) return;
    jbyte* coverage=env->GetByteArrayElements(mask,nullptr);
    if (!coverage) {env->ReleaseIntArrayElements(array,pixels,JNI_ABORT);return;}
    const int result=nf_local_exposure(reinterpret_cast<uint32_t*>(pixels),reinterpret_cast<uint8_t*>(coverage),count,ev);
    env->ReleaseByteArrayElements(mask,coverage,JNI_ABORT);
    env->ReleaseIntArrayElements(array,pixels,0);
    if (result!=0) invalid(env);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nijiframe_image_NativeTone_soften(JNIEnv* env, jobject, jintArray source, jintArray destination, jbyteArray mask,
    jint width, jint height, jint first_row, jint rows, jint radius, jfloat strength, jfloat protection) {
    if (!source || !destination || !mask || env->IsSameObject(source,destination) || width<=0 || height<=0 || rows<=0 ||
        static_cast<int64_t>(width)*height>env->GetArrayLength(source) ||
        static_cast<int64_t>(width)*rows>env->GetArrayLength(destination) ||
        static_cast<int64_t>(width)*rows>env->GetArrayLength(mask)) {invalid(env);return;}
    jint* input=env->GetIntArrayElements(source,nullptr);if(!input) return;
    jint* output=env->GetIntArrayElements(destination,nullptr);
    if(!output) {env->ReleaseIntArrayElements(source,input,JNI_ABORT);return;}
    jbyte* coverage=env->GetByteArrayElements(mask,nullptr);
    if(!coverage) {env->ReleaseIntArrayElements(source,input,JNI_ABORT);env->ReleaseIntArrayElements(destination,output,JNI_ABORT);return;}
    const int result=nf_soften(reinterpret_cast<uint32_t*>(input),reinterpret_cast<uint32_t*>(output),
        reinterpret_cast<uint8_t*>(coverage),width,height,first_row,rows,radius,strength,protection);
    env->ReleaseByteArrayElements(mask,coverage,JNI_ABORT);
    env->ReleaseIntArrayElements(source,input,JNI_ABORT);
    env->ReleaseIntArrayElements(destination,output,0);
    if(result!=0) invalid(env);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nijiframe_image_NativeTone_vignetteRegion(JNIEnv* env, jobject, jintArray array, jint width, jint height,
    jint full_width, jint full_height, jint left, jint top, jfloat amount) {
    if (!array || width <= 0 || height <= 0 || static_cast<int64_t>(width)*height > env->GetArrayLength(array)) {invalid(env);return;}
    jint* pixels=env->GetIntArrayElements(array,nullptr);if(!pixels) return;
    const int result=nf_vignette_region(reinterpret_cast<uint32_t*>(pixels),width,height,full_width,full_height,left,top,amount);
    env->ReleaseIntArrayElements(array,pixels,0);
    if(result!=0) invalid(env);
}
