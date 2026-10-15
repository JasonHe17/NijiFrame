// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.*

@Serializable data class MaskPoint(val x: Float, val y: Float)
@Serializable data class MaskStroke(val points: List<MaskPoint>, val radius: Float = .06f,
    val hardness: Float = .5f, val erase: Boolean = false)
@Serializable data class Region(val strokes: List<MaskStroke> = emptyList()) {
    fun validate() {
        require(strokes.size <= MAX_STROKES && strokes.sumOf { it.points.size } <= MAX_POINTS) { "区域笔画过多，请撤销部分笔画" }
        strokes.forEach { stroke ->
            require(stroke.points.isNotEmpty() && stroke.radius.isFinite() && stroke.radius in .005f.. .25f &&
                stroke.hardness.isFinite() && stroke.hardness in 0f..1f) { "Invalid brush" }
            require(stroke.points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f }) { "Invalid region coordinate" }
        }
    }
    fun validateWork(width: Int,height: Int) {
        require(width>0 && height>0)
        val scale=min(1.0,1024.0/max(width,height))
        val w=max(1,(width*scale).roundToInt());val h=max(1,(height*scale).roundToInt())
        var work=0L
        for(stroke in strokes) for(i in stroke.points.indices) {
            val a=stroke.points[max(0,i-1)];val b=stroke.points[i]
            val radius=stroke.radius*min(width,height)
            val bw=min(w,ceil((abs(a.x-b.x)*width+2*radius)/width*w).toInt()+2)
            val bh=min(h,ceil((abs(a.y-b.y)*height+2*radius)/height*h).toInt()+2)
            work+=bw.toLong()*bh
            require(work<=64_000_000) { "区域过于复杂，请撤销部分笔画" }
        }
    }
    companion object { const val MAX_STROKES=128; const val MAX_POINTS=4096 }
}
@Serializable data class LocalAdjustment(val exposureEv: Float = 0f,
    @Transient val region: Region = Region()) {
    fun validate() { require(exposureEv.isFinite() && exposureEv in -3f..3f);region.validate() }
}
@Serializable data class MaskResource(val path: String="assets/local-mask.json", val bytes: Int, val sha256: String)

/** Pixel-edge coordinates; normalize EXIF before using this mapping. */
class SourceGeometry(private val width: Int, private val height: Int, private val edit: Edit) {
    private val angle=edit.straighten*PI/180
    private val c=cos(angle);private val s=sin(angle)
    private val scale=Geometry.straightenScale(width,height,edit.straighten).toDouble()
    fun toOutput(point: MaskPoint): MaskPoint {
        val x=point.x*width-width/2.0;val y=point.y*height-height/2.0
        var xx=(x*c-y*s)*scale+width/2.0;var yy=(x*s+y*c)*scale+height/2.0
        if(edit.flipHorizontal) xx=width-xx
        if(edit.flipVertical) yy=height-yy
        val result=when(edit.turns) {1->height-yy to xx;2->width-xx to height-yy;3->yy to width-xx;else->xx to yy}
        return MaskPoint((result.first-(edit.crop?.x ?: 0)).toFloat(),(result.second-(edit.crop?.y ?: 0)).toFloat())
    }
    fun toSource(x: Float,y: Float): MaskPoint? {
        val (ow,oh)=Geometry.outputSize(width,height,edit)
        if(x !in 0f..ow.toFloat() || y !in 0f..oh.toFloat()) return null
        val ox=x+(edit.crop?.x ?: 0);val oy=y+(edit.crop?.y ?: 0)
        val p=when(edit.turns) {1->oy to height-ox;2->width-ox to height-oy;3->width-oy to ox;else->ox to oy}
        val xx=(if(edit.flipHorizontal) width-p.first else p.first)-width/2.0
        val yy=(if(edit.flipVertical) height-p.second else p.second)-height/2.0
        val sx=((xx*c+yy*s)/scale+width/2.0)/width
        val sy=((-xx*s+yy*c)/scale+height/2.0)/height
        return if(sx in -.000001..1.000001 && sy in -.000001..1.000001)
            MaskPoint(sx.toFloat().coerceIn(0f,1f),sy.toFloat().coerceIn(0f,1f)) else null
    }
}

/** Bounded 8-bit coverage grid. Resolution and sampling are part of pipeline v3. */
class MaskRaster(val width: Int,val height: Int,val coverage: ByteArray) {
    fun sample(x: Float,y: Float): Int {
        val px=(x*width-.5f).coerceIn(0f,(width-1).toFloat());val py=(y*height-.5f).coerceIn(0f,(height-1).toFloat())
        val ix=px.toInt();val iy=py.toInt();val jx=min(ix+1,width-1);val jy=min(iy+1,height-1)
        fun v(xx:Int,yy:Int)=coverage[yy*width+xx].toInt() and 255
        val top=v(ix,iy)*(1-(px-ix))+v(jx,iy)*(px-ix)
        val bottom=v(ix,jy)*(1-(px-ix))+v(jx,jy)*(px-ix)
        return (top*(1-(py-iy))+bottom*(py-iy)).roundToInt().coerceIn(0,255)
    }
    companion object {
        fun create(region: Region,sourceWidth: Int,sourceHeight: Int,check: ()->Unit={}): MaskRaster {
            region.validate();region.validateWork(sourceWidth,sourceHeight)
            val scale=min(1.0,1024.0/max(sourceWidth,sourceHeight))
            val w=max(1,(sourceWidth*scale).roundToInt());val h=max(1,(sourceHeight*scale).roundToInt())
            val out=ByteArray(w*h);val scratch=ByteArray(w*h)
            var visited=0L
            for(stroke in region.strokes) {
                check();scratch.fill(0)
                val radius=stroke.radius*min(sourceWidth,sourceHeight)
                val points=stroke.points
                for(i in points.indices) {
                    val a=points[max(0,i-1)];val b=points[i]
                    val ax=a.x*sourceWidth;val ay=a.y*sourceHeight;val bx=b.x*sourceWidth;val by=b.y*sourceHeight
                    val dx=bx-ax;val dy=by-ay;val length=dx*dx+dy*dy
                    val x0=floor((min(ax,bx)-radius)/sourceWidth*w).toInt().coerceIn(0,w-1)
                    val x1=ceil((max(ax,bx)+radius)/sourceWidth*w).toInt().coerceIn(0,w-1)
                    val y0=floor((min(ay,by)-radius)/sourceHeight*h).toInt().coerceIn(0,h-1)
                    val y1=ceil((max(ay,by)+radius)/sourceHeight*h).toInt().coerceIn(0,h-1)
                    visited+=(x1-x0+1).toLong()*(y1-y0+1)
                    require(visited<=64_000_000) { "区域过于复杂，请撤销部分笔画" }
                    for(y in y0..y1) {
                        check()
                        for(x in x0..x1) {
                            val px=(x+.5f)/w*sourceWidth;val py=(y+.5f)/h*sourceHeight
                            val t=if(length==0f) 0f else (((px-ax)*dx+(py-ay)*dy)/length).coerceIn(0f,1f)
                            val d=hypot(px-ax-t*dx,py-ay-t*dy)/radius
                            val value=if(d>=1f) 0 else if(d<=stroke.hardness) 255
                                else ((1f-d)/(1f-stroke.hardness)*255).roundToInt()
                            val index=y*w+x
                            if(value>(scratch[index].toInt() and 255)) scratch[index]=value.toByte()
                        }
                    }
                }
                for(i in out.indices) {
                    val value=out[i].toInt() and 255;val brush=scratch[i].toInt() and 255
                    // Union for add; subtraction for erase. Neither accumulates exposure.
                    out[i]=(if(stroke.erase) min(value,255-brush) else max(value,brush)).toByte()
                }
            }
            return MaskRaster(w,h,out)
        }
    }
}
