// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import org.junit.Assert.*
import org.junit.Test

class FaceSearchTest {
    private fun rectangle(x:Float,y:Float,w:Float,h:Float)=listOf(MaskPoint(x,y),MaskPoint(x+w,y),MaskPoint(x+w,y+h),MaskPoint(x,y+h))
    @Test fun oddSizedWindowsCoverImageAndMapBackToSource() {
        val w=641;val h=853
        val windows=FaceSearch.windows(w,h)
        assertEquals(5,windows.size)
        for(y in 0 until h) for(x in 0 until w) assertTrue(windows.any {x>=it.x && x<it.x+it.width && y>=it.y && y<it.y+it.height})
        for(crop in windows) {
            val points=FaceSearch.toSource(listOf(MaskPoint(0f,0f),MaskPoint(1f,1f)),crop,w,h)
            assertEquals(crop.x.toFloat(),points[0].x*w,.0001f)
            assertEquals(crop.y.toFloat(),points[0].y*h,.0001f)
            assertEquals((crop.x+crop.width).toFloat(),points[1].x*w,.0001f)
            assertEquals((crop.y+crop.height).toFloat(),points[1].y*h,.0001f)
        }
    }
    @Test fun repeatsMergeButSeparateFacesNeverBecomeTheFirstMatch() {
        val a=rectangle(.3f,.2f,.1f,.2f);val repeat=rectangle(.305f,.205f,.1f,.2f)
        assertTrue(FaceSearch.sameLocation(a,repeat));assertTrue(FaceSearch.sameLocation(repeat,a))
        val b=rectangle(.65f,.2f,.1f,.2f)
        assertFalse(FaceSearch.sameLocation(a,b));assertFalse(FaceSearch.sameLocation(b,a))
        assertFalse(FaceSearch.sameLocation(a,rectangle(.3f,.2f,0f,0f)))
        assertThrows(IllegalArgumentException::class.java) {FaceSearch.toSource(a,Crop(90,0,20,10),100,100)}
    }
    @Test fun windowCoverageRetainsItsSourceLocationThroughEditingGeometry() {
        val w=400;val h=600
        val window=Crop(100,150,200,300)
        val local=rectangle(.2f,.2f,.4f,.4f)
        val mapped=FaceSearch.toSource(local,window,w,h)
        assertEquals(MaskPoint(.35f,.35f),mapped[0])
        val edit=Edit(turns=1,flipHorizontal=true,straighten=10f,crop=Crop(10,10,570,370))
        val geometry=SourceGeometry(w,h,edit)
        for(p in mapped) {
            val out=geometry.toOutput(p);val restored=requireNotNull(geometry.toSource(out.x,out.y))
            assertEquals(p.x,restored.x,.00001f);assertEquals(p.y,restored.y,.00001f)
        }
    }
}
