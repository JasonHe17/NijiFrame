// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import kotlin.math.*

/** Geometry only; callers own inference, confidence thresholds and cancellation. */
object FaceSearch {
    /** Five overlapping half-size views of the EXIF-normalized source proxy. */
    fun windows(width: Int, height: Int): List<Crop> {
        require(width >= 2 && height >= 2)
        return listOf(.25 to .25,0.0 to 0.0,.5 to 0.0,0.0 to .5,.5 to .5).map { (x,y) ->
            val left=(x*width).roundToInt();val top=(y*height).roundToInt()
            val right=((x+.5)*width).roundToInt();val bottom=((y+.5)*height).roundToInt()
            Crop(left,top,right-left,bottom-top)
        }.distinct()
    }
    fun toSource(points: List<MaskPoint>, window: Crop, width: Int, height: Int): List<MaskPoint> {
        require(width>0 && height>0 && window.x>=0 && window.y>=0 && window.width>0 && window.height>0 &&
            window.x.toLong()+window.width<=width && window.y.toLong()+window.height<=height)
        require(points.isNotEmpty() && points.all {it.x.isFinite() && it.y.isFinite()})
        return points.map {MaskPoint((window.x+it.x*window.width)/width,(window.y+it.y*window.height)/height)}
    }
    private fun bounds(points: List<MaskPoint>): FloatArray {
        require(points.isNotEmpty() && points.all {it.x.isFinite() && it.y.isFinite()})
        return floatArrayOf(points.minOf {it.x},points.minOf {it.y},points.maxOf {it.x},points.maxOf {it.y})
    }
    /** This compares locations, not identities. An ambiguous set must never become the first match. */
    fun sameLocation(a: List<MaskPoint>, b: List<MaskPoint>): Boolean {
        val x=bounds(a);val y=bounds(b)
        val overlap=max(0f,min(x[2],y[2])-max(x[0],y[0]))*max(0f,min(x[3],y[3])-max(x[1],y[1]))
        val union=(x[2]-x[0])*(x[3]-x[1])+(y[2]-y[0])*(y[3]-y[1])-overlap
        return union>0f && overlap/union>=.5f
    }
}
