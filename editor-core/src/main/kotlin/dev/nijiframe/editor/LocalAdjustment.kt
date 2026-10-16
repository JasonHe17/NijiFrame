// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.*

@Serializable data class MaskPoint(val x: Float, val y: Float)
@Serializable data class MaskStroke(val points: List<MaskPoint>, val radius: Float = .06f,
    val hardness: Float = .5f, val erase: Boolean = false)
/** Persisted suggestion; immutable lowercase hex, one unsigned coverage byte per pixel. */
@Serializable data class BaseCoverage(val width: Int, val height: Int, val hex: String) {
    fun validate() {
        require(width in 1..256 && height in 1..256 && hex.length == width * height * 2 &&
            hex.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid base coverage" }
    }
    fun raster(): MaskRaster {
        validate()
        return MaskRaster(width, height, ByteArray(width * height) { i ->
            ((hex[i*2].digitToInt(16) shl 4) or hex[i*2+1].digitToInt(16)).toByte()
        })
    }
    companion object {
        fun from(raster: MaskRaster): BaseCoverage {
            val digits = "0123456789abcdef"
            val text = buildString(raster.coverage.size * 2) {
                for (byte in raster.coverage) { val v=byte.toInt() and 255; append(digits[v shr 4]); append(digits[v and 15]) }
            }
            return BaseCoverage(raster.width, raster.height, text).also { it.validate() }
        }
    }
}
@Serializable data class Region(val strokes: List<MaskStroke> = emptyList(), val base: BaseCoverage? = null,
    val refinements: List<MaskStroke> = emptyList()) {
    val isEmpty: Boolean get() = strokes.isEmpty() && base == null && refinements.isEmpty()
    fun validate() {
        base?.validate()
        val all=strokes+refinements
        require(all.size <= MAX_STROKES && all.sumOf { it.points.size } <= MAX_POINTS) { "区域笔画过多，请撤销部分笔画" }
        all.forEachIndexed { index, stroke ->
            val minimum=if(index<strokes.size) .005f else 1f/65536
            require(stroke.points.isNotEmpty() && stroke.radius.isFinite() && stroke.radius in minimum.. .25f &&
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
        var preciseWork=0L
        for(stroke in refinements) for(i in stroke.points.indices) {
            val a=stroke.points[max(0,i-1)];val b=stroke.points[i]
            val radius=stroke.radius*min(width,height)
            val bw=min(width.toDouble(),ceil(abs(a.x-b.x)*width+2.0*radius)+2).toLong()
            val bh=min(height.toDouble(),ceil(abs(a.y-b.y)*height+2.0*radius)+2).toLong()
            preciseWork+=bw*bh
            require(preciseWork<=64_000_000) { "精细区域过于复杂，请撤销部分笔画" }
        }
    }
    companion object { const val MAX_STROKES=128; const val MAX_POINTS=4096 }
}
@Serializable data class LocalAdjustment(val exposureEv: Float = 0f,
    @Transient val region: Region = Region(), val soften: Float = 0f, val detailProtection: Float = .75f,
    val warmth: Float = 0f, val tint: Float = 0f, val saturation: Float = 1f) {
    val hasColor: Boolean get()=exposureEv!=0f || warmth!=0f || tint!=0f || saturation!=1f
    val hasEffect: Boolean get()=hasColor || soften!=0f
    fun validate() {
        require(exposureEv.isFinite() && exposureEv in -3f..3f)
        require(soften.isFinite() && soften in 0f..1f && detailProtection.isFinite() && detailProtection in 0f..1f) { "Invalid softening parameters" }
        require(warmth.isFinite() && warmth in -1f..1f && tint.isFinite() && tint in -1f..1f &&
            saturation.isFinite() && saturation in 0f..2f) { "Invalid local color" }
        region.validate()
    }
}
@Serializable data class LocalRegion(val id: String, val name: String, val adjustment: LocalAdjustment = LocalAdjustment(),
    val enabled: Boolean = true) {
    fun validate() {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,64}"))) { "Invalid region identifier" }
        require(name.isNotBlank() && name.length<=40 && name.none {it.isISOControl()}) { "Invalid region name" }
        adjustment.validate()
    }
    companion object { const val MAX_REGIONS=4 }
}
/** v7 resource payload. Parameters remain in the manifest, coverage is integrity checked separately. */
@Serializable internal data class LocalMasks(val legacy: Region = Region(), val regions: Map<String, Region> = emptyMap())
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
            require(region.refinements.isEmpty()) { "Use RegionCoverage for source-resolution corrections" }
            region.validate();region.validateWork(sourceWidth,sourceHeight)
            val scale=min(1.0,1024.0/max(sourceWidth,sourceHeight))
            val w=max(1,(sourceWidth*scale).roundToInt());val h=max(1,(sourceHeight*scale).roundToInt())
            val out=ByteArray(w*h);val scratch=ByteArray(w*h)
            region.base?.raster()?.let { base ->
                for(y in 0 until h) { check(); for(x in 0 until w) out[y*w+x]=base.sample((x+.5f)/w,(y+.5f)/h).toByte() }
            }
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
