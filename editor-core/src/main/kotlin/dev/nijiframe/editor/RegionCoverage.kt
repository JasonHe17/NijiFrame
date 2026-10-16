// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import kotlin.math.*

/** Legacy coverage plus ordered source-resolution corrections; no full-size mask allocation. */
class RegionCoverage(private val region: Region, private val sourceWidth: Int, private val sourceHeight: Int,
                     private val check: () -> Unit = {}) {
    private val legacy: MaskRaster
    private data class Segment(val stroke: Int, val ax: Float, val ay: Float, val dx: Float, val dy: Float,
        val radius: Float, val hardness: Float, val erase: Boolean)
    // Only overlays need random samples. Lazy indexing avoids visiting every remote
    // fine segment for every overview pixel; strip rendering does not allocate it.
    private val buckets by lazy {
        Array(256) {ArrayList<Segment>()}.also {index ->
            region.refinements.forEachIndexed {number,stroke ->
                val radius=stroke.radius*min(sourceWidth,sourceHeight)
                for(i in stroke.points.indices) {
                    check()
                    val a=stroke.points[max(0,i-1)];val b=stroke.points[i]
                    val ax=a.x*sourceWidth;val ay=a.y*sourceHeight;val bx=b.x*sourceWidth;val by=b.y*sourceHeight
                    val entry=Segment(number,ax,ay,bx-ax,by-ay,radius,stroke.hardness,stroke.erase)
                    val x0=floor((min(ax,bx)-radius)/sourceWidth*16).toInt().coerceIn(0,15)
                    val x1=floor((max(ax,bx)+radius)/sourceWidth*16).toInt().coerceIn(0,15)
                    val y0=floor((min(ay,by)-radius)/sourceHeight*16).toInt().coerceIn(0,15)
                    val y1=floor((max(ay,by)+radius)/sourceHeight*16).toInt().coerceIn(0,15)
                    for(y in y0..y1) for(x in x0..x1) index[y*16+x].add(entry)
                }
            }
        }
    }
    init {
        region.validate();region.validateWork(sourceWidth,sourceHeight)
        legacy=if(region.strokes.isEmpty() && region.base==null) MaskRaster(1,1,byteArrayOf(0)) else
            MaskRaster.create(region.copy(refinements=emptyList()),sourceWidth,sourceHeight,check)
    }
    fun window(left: Int, top: Int, width: Int, height: Int): ByteArray {
        require(left>=0 && top>=0 && width>0 && height>0 &&
            left.toLong()+width<=sourceWidth && top.toLong()+height<=sourceHeight && width.toLong()*height<=2_000_000)
        val result=ByteArray(width*height)
        for(y in 0 until height) {
            check()
            for(x in 0 until width) result[y*width+x]=legacy.sample((left+x+.5f)/sourceWidth,(top+y+.5f)/sourceHeight).toByte()
        }
        if(region.refinements.isEmpty()) return result
        val scratch=ByteArray(result.size)
        for(stroke in region.refinements) {
            check();scratch.fill(0)
            val radius=stroke.radius*min(sourceWidth,sourceHeight)
            for(i in stroke.points.indices) {
                val a=stroke.points[max(0,i-1)];val b=stroke.points[i]
                val ax=a.x*sourceWidth;val ay=a.y*sourceHeight;val bx=b.x*sourceWidth;val by=b.y*sourceHeight
                val x0=max(left,floor(min(ax,bx)-radius).toInt())
                val x1=min(left+width-1,ceil(max(ax,bx)+radius).toInt())
                val y0=max(top,floor(min(ay,by)-radius).toInt())
                val y1=min(top+height-1,ceil(max(ay,by)+radius).toInt())
                val dx=bx-ax;val dy=by-ay;val length=dx*dx+dy*dy
                for(y in y0..y1) {
                    check()
                    for(x in x0..x1) {
                        val px=x+.5f;val py=y+.5f
                        val t=if(length==0f) 0f else (((px-ax)*dx+(py-ay)*dy)/length).coerceIn(0f,1f)
                        val d=hypot(px-ax-t*dx,py-ay-t*dy)/radius
                        val value=if(d>=1f) 0 else if(d<=stroke.hardness) 255
                            else ((1-d)/(1-stroke.hardness)*255).roundToInt()
                        val at=(y-top)*width+x-left
                        if(value>(scratch[at].toInt() and 255)) scratch[at]=value.toByte()
                    }
                }
            }
            for(i in result.indices) {
                val old=result[i].toInt() and 255;val brush=scratch[i].toInt() and 255
                result[i]=(if(stroke.erase) min(old,255-brush) else max(old,brush)).toByte()
            }
        }
        return result
    }
    /** Overview and transformed overlay sampling uses the exact same correction formula. */
    fun sample(x: Float,y: Float): Int {
        var value=legacy.sample(x,y)
        if(region.refinements.isEmpty()) return value
        val px=x*sourceWidth;val py=y*sourceHeight
        var previous=-1;var brush=0;var erase=false
        fun flush() {value=if(erase) min(value,255-brush) else max(value,brush)}
        val candidates=buckets[(y*16).toInt().coerceIn(0,15)*16+(x*16).toInt().coerceIn(0,15)]
        for(s in candidates) {
            if(s.stroke!=previous) {flush();previous=s.stroke;brush=0;erase=s.erase}
            val length=s.dx*s.dx+s.dy*s.dy
            val t=if(length==0f) 0f else (((px-s.ax)*s.dx+(py-s.ay)*s.dy)/length).coerceIn(0f,1f)
            val d=hypot(px-s.ax-t*s.dx,py-s.ay-t*s.dy)/s.radius
            val next=if(d>=1f) 0 else if(d<=s.hardness) 255 else ((1-d)/(1-s.hardness)*255).roundToInt()
            brush=max(brush,next)
        }
        flush()
        return value
    }
}
