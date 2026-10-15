// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.graphics.*
import dev.nijiframe.editor.*
import dev.nijiframe.image.NativeTone
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.*

/** A bounded original-resolution window in the final, cropped output coordinate system. */
internal object DetailRenderer {
    const val MAX_EDGE = 768
    data class Frame(val bitmap: Bitmap, val bounds: Rect, val outputWidth: Int, val outputHeight: Int)

    suspend fun regionOverlay(width: Int,height: Int,edit: Edit,bounds: Rect): Bitmap {
        require(bounds.width() in 1..MAX_EDGE && bounds.height() in 1..MAX_EDGE)
        val context=currentCoroutineContext()
        val mask=MaskRaster.create(edit.local.region,width,height) {context.ensureActive()}
        val mapping=SourceGeometry(width,height,edit)
        val pixels=IntArray(bounds.width()*bounds.height())
        for(y in 0 until bounds.height()) {
            context.ensureActive()
            for(x in 0 until bounds.width()) {
                val point=mapping.toSource(bounds.left+x+.5f,bounds.top+y+.5f)
                val coverage=point?.let {mask.sample(it.x,it.y)} ?: 0
                pixels[y*bounds.width()+x]=(coverage*110/255 shl 24) or 0x00ff4060
            }
        }
        return Bitmap.createBitmap(pixels,bounds.width(),bounds.height(),Bitmap.Config.ARGB_8888)
    }

    suspend fun render(project: Project, edit: Edit, center: MaskPoint, width: Int, height: Int): Frame {
        val m=project.manifest
        edit.validate(m.width,m.height)
        require(center.x.isFinite() && center.y.isFinite() && center.x in 0f..1f && center.y in 0f..1f)
        require(width in 1..MAX_EDGE && height in 1..MAX_EDGE)
        val context=currentCoroutineContext()
        context.ensureActive()
        val (ow,oh)=Geometry.outputSize(m.width,m.height,edit)
        val w=min(width,ow);val h=min(height,oh)
        val x=(center.x*ow-w/2f).roundToInt().coerceIn(0,ow-w)
        val y=(center.y*oh-h/2f).roundToInt().coerceIn(0,oh-h)
        val wanted=Rect(x,y,x+w,y+h)
        val halo=if(edit.sharpen==0f) 0 else 1
        val expanded=expand(wanted,halo,ow,oh)
        val crop=edit.crop
        val canvas=Rect(expanded).apply {offset(crop?.x ?: 0,crop?.y ?: 0)}
        val (cw,ch)=Geometry.canvasSize(m.width,m.height,edit.turns)
        val unturned=Geometry.legacyCrop(Crop(canvas.left,canvas.top,canvas.width(),canvas.height()),cw,ch,(4-edit.turns)%4)
        val sx=if(edit.flipHorizontal) m.width-unturned.x-unturned.width else unturned.x
        val sy=if(edit.flipVertical) m.height-unturned.y-unturned.height else unturned.y
        val straightBounds=Rect(sx,sy,sx+unturned.width,sy+unturned.height)
        val straightMatrix=Matrix().apply {
            postTranslate(-m.width/2f,-m.height/2f)
            postRotate(edit.straighten)
            val scale=Geometry.straightenScale(m.width,m.height,edit.straighten)
            postScale(scale,scale)
            postTranslate(m.width/2f,m.height/2f)
        }
        val sourceBounds=if(edit.straighten==0f) Rect(straightBounds) else {
            val inverse=Matrix();check(straightMatrix.invert(inverse))
            val box=RectF(straightBounds);inverse.mapRect(box)
            Rect(floor(box.left).toInt()-2,floor(box.top).toInt()-2,ceil(box.right).toInt()+2,ceil(box.bottom).toInt()+2)
        }
        val radius=if(edit.local.soften>0f) (min(m.width,m.height)/600f).roundToInt().coerceIn(1,6) else 0
        val info=ImagePipeline.inspect(project.original,project.manifest.honorExifColorSpace)
        require(info.width==m.width && info.height==m.height) {"工程尺寸与原图不一致"}
        // JPEG chroma interpolation also needs neighbors beyond the requested region, even with neutral effects.
        val sourceRect=expand(sourceBounds,radius+if(info.mime=="image/jpeg") 16 else 0,m.width,m.height)
        require(sourceRect.width()>0 && sourceRect.height()>0 && sourceRect.width().toLong()*sourceRect.height()<=2_000_000) {
            "此区域暂时无法查看细节，请选择其他位置"
        }
        val decoded=PhotoInput.decodeRegion(project.original,info,sourceRect)
        var output: Bitmap?=null
        try {
            context.ensureActive()
            val colored=ImagePipeline.colorRegion(decoded,edit,m.width,m.height,sourceRect.left,sourceRect.top)
            try {
                output=if(edit.straighten==0f) Bitmap.createBitmap(colored,straightBounds.left-sourceRect.left,
                    straightBounds.top-sourceRect.top,straightBounds.width(),straightBounds.height()) else {
                    val target=Bitmap.createBitmap(straightBounds.width(),straightBounds.height(),Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(target).apply {
                            translate(-straightBounds.left.toFloat(),-straightBounds.top.toFloat())
                            concat(straightMatrix)
                            drawBitmap(colored,sourceRect.left.toFloat(),sourceRect.top.toFloat(),Paint(Paint.FILTER_BITMAP_FLAG))
                        }
                        target
                    } catch(error: Throwable) {target.recycle();throw error}
                }
            } finally {if(output!==colored) colored.recycle()}
            context.ensureActive()
            if(edit.flipHorizontal || edit.flipVertical || edit.turns!=0) {
                val current=requireNotNull(output)
                val matrix=Matrix().apply {
                    setScale(if(edit.flipHorizontal) -1f else 1f,if(edit.flipVertical) -1f else 1f)
                    postRotate(edit.turns*90f)
                }
                output=Bitmap.createBitmap(current,0,0,current.width,current.height,matrix,false)
                if(output!==current) current.recycle()
            }
            var current=requireNotNull(output)
            check(current.width==expanded.width() && current.height==expanded.height())
            if(edit.sharpen!=0f) {
                val pixels=IntArray(current.width*current.height)
                current.getPixels(pixels,0,current.width,0,0,current.width,current.height)
                val sharp=IntArray(pixels.size)
                NativeTone.sharpen(pixels,sharp,current.width,current.height,edit.sharpen)
                output=Bitmap.createBitmap(sharp,current.width,current.height,Bitmap.Config.ARGB_8888)
                current.recycle();current=requireNotNull(output)
            }
            context.ensureActive()
            output=Bitmap.createBitmap(current,wanted.left-expanded.left,wanted.top-expanded.top,w,h)
            if(output!==current) current.recycle()
            current=requireNotNull(output)
            if(edit.vignette!=0f) {
                val pixels=IntArray(w*h);current.getPixels(pixels,0,w,0,0,w,h)
                NativeTone.vignetteRegion(pixels,w,h,ow,oh,wanted.left,wanted.top,edit.vignette)
                output=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
                current.recycle()
            }
            context.ensureActive()
            return Frame(requireNotNull(output),wanted,ow,oh).also {output=null}
        } finally {decoded.recycle();output?.recycle()}
    }

    private fun expand(rect: Rect, halo: Int, width: Int, height: Int)=Rect(
        (rect.left-halo).coerceIn(0,width),(rect.top-halo).coerceIn(0,height),
        (rect.right+halo).coerceIn(0,width),(rect.bottom+halo).coerceIn(0,height))

}
