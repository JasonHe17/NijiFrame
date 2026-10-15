// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.components.containers.Connection
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import dev.nijiframe.editor.*
import kotlin.math.*

/** A geometric starting region, not skin segmentation or an identity/quality classifier. */
internal object PortraitRegion {
    const val MODEL = "face_landmarker.task"
    fun suggest(context: Context, source: Bitmap, check: () -> Unit = {}): Region {
        check()
        // The caller serializes this work with rendering and checks memory before model creation.
        val scale=min(1.0,1024.0/max(source.width,source.height))
        val input=Bitmap.createScaledBitmap(source,max(1,(source.width*scale).roundToInt()),
            max(1,(source.height*scale).roundToInt()),true)
        // MPImage owns its bitmap; never hand it the editor's shared source.
        val owned=if(input===source) source.copy(Bitmap.Config.ARGB_8888,false) else input
        val image=BitmapImageBuilder(owned).build()
        try {
            val options=FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.IMAGE).setNumFaces(2)
                .setMinFaceDetectionConfidence(.5f).setMinFacePresenceConfidence(.5f)
                .setOutputFaceBlendshapes(false).setOutputFacialTransformationMatrixes(false).build()
            val detector=FaceLandmarker.createFromOptions(context,options)
            try {
                check()
                val faces=detector.detect(image).faceLandmarks()
                check()
                require(faces.isNotEmpty()) { "未找到清晰人脸。侧脸、遮挡或小人脸可能识别失败，请继续手工涂选" }
                require(faces.size==1) { "检测到多张人脸，暂不提供多人区域建议，请继续手工涂选" }
                val landmarks=faces.single()
                require(landmarks.size>=468) { "人脸关键点不完整，请继续手工涂选" }
                fun hull(connections: Set<Connection>): List<MaskPoint> = convexHull(
                    connections.flatMap { listOf(it.start(),it.end()) }.distinct().map { i ->
                        val p=landmarks[i]
                        require(p.x().isFinite() && p.y().isFinite() && p.x() in -.25f..1.25f && p.y() in -.25f..1.25f) {
                            "人脸轮廓超出画面过多，请继续手工涂选"
                        }
                        // Allow a slightly clipped face; rasterization naturally clips to the image.
                        MaskPoint(p.x()*owned.width,p.y()*owned.height)
                    })
                val face=hull(FaceLandmarker.FACE_LANDMARKS_FACE_OVAL)
                val excluded=listOf(FaceLandmarker.FACE_LANDMARKS_LEFT_EYE,FaceLandmarker.FACE_LANDMARKS_RIGHT_EYE,
                    FaceLandmarker.FACE_LANDMARKS_LEFT_EYE_BROW,FaceLandmarker.FACE_LANDMARKS_RIGHT_EYE_BROW,
                    FaceLandmarker.FACE_LANDMARKS_LIPS).map(::hull)
                val faceWidth=face.maxOf {it.x}-face.minOf {it.x}
                require(faceWidth>=40 && face.maxOf {it.y}-face.minOf {it.y}>=40) { "人脸太小，建议使用手工区域" }
                val gridScale=min(1.0,256.0/max(owned.width,owned.height))
                val w=max(1,(owned.width*gridScale).roundToInt());val h=max(1,(owned.height*gridScale).roundToInt())
                val bytes=ByteArray(w*h)
                val padding=max(2f,faceWidth*.015f);val feather=max(2f,faceWidth*.025f)
                for(y in 0 until h) {
                    check()
                    for(x in 0 until w) {
                        val p=MaskPoint((x+.5f)/w*owned.width,(y+.5f)/h*owned.height)
                        var distance=signedDistance(p,face)
                        if(distance<=0) continue
                        for(hole in excluded) distance=min(distance,-signedDistance(p,hole)-padding)
                        bytes[y*w+x]=(distance/feather*255).roundToInt().coerceIn(0,255).toByte()
                    }
                }
                require(bytes.any {it.toInt() and 255 > 128}) { "未能生成可用区域，请继续手工涂选" }
                return Region(base=BaseCoverage.from(MaskRaster(w,h,bytes)))
            } finally { detector.close() }
        } finally { image.close();if(!owned.isRecycled) owned.recycle() }
    }

    private fun convexHull(input: List<MaskPoint>): List<MaskPoint> {
        val sorted=input.distinct().sortedWith(compareBy({it.x},{it.y}))
        fun cross(a:MaskPoint,b:MaskPoint,c:MaskPoint)=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
        fun half(points: List<MaskPoint>): List<MaskPoint> {
            val result=ArrayList<MaskPoint>()
            for(p in points) { while(result.size>=2 && cross(result[result.size-2],result.last(),p)<=0) result.removeAt(result.lastIndex);result.add(p) }
            return result.dropLast(1)
        }
        return (half(sorted)+half(sorted.reversed())).also { require(it.size>=3) { "无法确定人脸区域，请手工涂选" } }
    }
    /** Positive inside a convex CCW hull; distance uses image pixels, preserving aspect ratio. */
    private fun signedDistance(p:MaskPoint,polygon:List<MaskPoint>): Float {
        var inside=true;var distance=Float.MAX_VALUE
        for(i in polygon.indices) {
            val a=polygon[i];val b=polygon[(i+1)%polygon.size]
            val dx=b.x-a.x;val dy=b.y-a.y
            if(dx*(p.y-a.y)-dy*(p.x-a.x)<0) inside=false
            val t=(((p.x-a.x)*dx+(p.y-a.y)*dy)/(dx*dx+dy*dy)).coerceIn(0f,1f)
            distance=min(distance,hypot(p.x-a.x-t*dx,p.y-a.y-t*dy))
        }
        return if(inside) distance else -distance
    }
}
