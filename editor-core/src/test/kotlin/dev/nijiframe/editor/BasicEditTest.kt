// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

class BasicEditTest {
    @Test fun originalVersionOnePackageMigratesWithoutChangingParameters() {
        val source = byteArrayOf(1,2,3)
        val manifest = """{"version":1,"pipeline":"srgb-tone-crop-turn-v1","source":"assets/original", "sourceBytes":3,
            "sourceSha256":"${ProjectIO.digest(source)}","width":3,"height":1,
            "edit":{"exposureEv":1.0,"saturation":0.5,"turns":1,"crop":null}}"""
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            for ((name,payload) in listOf("project.json" to manifest.encodeToByteArray(), "assets/original" to source)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(payload); zip.closeEntry()
            }
        }}.toByteArray()
        val result = ProjectIO.read(bytes.inputStream())
        assertEquals(4, result.manifest.version)
        assertEquals(Edit(1f,0.5f,1), result.manifest.edit)
        assertArrayEquals(source, result.original)
    }
    @Test fun legacyNonSquareCropMovesIntoRotatedCoordinates() {
        val original=ProjectIO.create(byteArrayOf(1),40,20)
        val legacy=original.copy(manifest=original.manifest.copy(version=1,pipeline="srgb-tone-crop-turn-v1",
            edit=Edit(turns=1,crop=Crop(10,0,30,20))))
        val bytes=ByteArrayOutputStream().also {ProjectIO.write(legacy,it)}.toByteArray()
        val current=ProjectIO.read(bytes.inputStream())
        assertEquals(Crop(0,10,20,30),current.manifest.edit.crop)
        assertEquals(20 to 30,Geometry.outputSize(40,20,current.manifest.edit))
    }

    @Test fun newAdjustmentsRoundTripAndRejectOldVersion() {
        val edit = Edit(0.5f, 1.2f, 3, Crop(1,1,5,7), contrast=.2f, highlights=-.3f, shadows=.2f,
            whites=.1f, blacks=-.1f, warmth=.4f, tint=-.2f, vibrance=.5f, sharpen=.8f, vignette=.3f,
            flipHorizontal=true, flipVertical=true, straighten=-12f)
        val project = ProjectIO.create(byteArrayOf(1,2), 10, 10).withEdit(edit)
        val bytes = ByteArrayOutputStream().also { ProjectIO.write(project,it) }.toByteArray()
        assertEquals(project.manifest, ProjectIO.read(bytes.inputStream()).manifest)
        assertThrows(IllegalArgumentException::class.java) {
            project.manifest.copy(version=1, pipeline="srgb-tone-crop-turn-v1").validate()
        }
        assertThrows(IllegalArgumentException::class.java) { edit.copy(straighten=Float.NaN).validate(10,10) }
        assertThrows(IllegalArgumentException::class.java) { edit.copy(sharpen=3f).validate(10,10) }
    }
    @Test fun straightenScaleCoversAllOutputCornersForBothSignsAndAspectRatios() {
        for ((w,h) in listOf(1200 to 800, 800 to 1200, 100 to 100, 20 to 800)) {
            for (angle in listOf(-45f,-15f,0f,15f,45f)) {
                val scale = Geometry.straightenScale(w,h,angle)
                val a = Math.toRadians(angle.toDouble()); val c=cos(a); val s=sin(a)
                for (x in listOf(-w/2.0,w/2.0)) for (y in listOf(-h/2.0,h/2.0)) {
                    assertTrue(abs((x*c+y*s)/scale) <= w/2.0+.001)
                    assertTrue(abs((-x*s+y*c)/scale) <= h/2.0+.001)
                }
            }
        }
    }
    @Test fun cropHandlesClampAndPreserveAspectAndSelectedContent() {
        val crop=Crop(20,30,200,100)
        assertEquals(Crop(0,0,200,100),CropGesture.drag(crop,-1000f,-1000f,400,300,CropHandle.MOVE,null))
        assertEquals(Crop(200,200,200,100),CropGesture.drag(crop,1000f,1000f,400,300,CropHandle.MOVE,null))
        val resized=CropGesture.drag(crop,80f,40f,400,300,CropHandle.BOTTOM_RIGHT,2f)
        assertEquals(Crop(20,30,280,140),resized)
        for(handle in CropHandle.entries) for(delta in listOf(-1000f,0f,1000f)) {
            val result=CropGesture.drag(crop,delta,-delta,400,300,handle,2f)
            Edit(crop=result).validate(400,300)
        }
        val original=Edit(crop=crop)
        val rotated=Geometry.rotate(original,400,300)
        assertEquals(Crop(170,20,100,200),rotated.crop)
        var roundTrip=rotated
        repeat(3) {roundTrip=Geometry.rotate(roundTrip,400,300)}
        assertEquals(original,roundTrip)
        assertEquals(original,Geometry.flip(Geometry.flip(original,400,300,true),400,300,true))
    }

    @Test fun presetsPreserveGeometryAndExportsNeverUpscale() {
        val geometry = Edit(turns=1,crop=Crop(2,3,100,200),straighten=8f,flipHorizontal=true)
        for (look in Look.entries) {
            val result=look.apply(geometry);result.validate(400,600)
            assertEquals(geometry.crop,result.crop);assertEquals(geometry.straighten,result.straighten)
            assertEquals(geometry.turns,result.turns);assertTrue(result.flipHorizontal)
        }
        assertEquals(100 to 200, Geometry.outputSize(400,600,geometry))
        assertEquals(600 to 400, ExportSettings(maxEdge=600).dimensions(1200,800))
        assertEquals(120 to 80, ExportSettings(maxEdge=600).dimensions(120,80))
        assertEquals(Crop(100,0,400,400), Geometry.centeredCrop(600,400,1f))
    }
}
