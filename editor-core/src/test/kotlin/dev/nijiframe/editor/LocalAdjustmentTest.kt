// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.*

class LocalAdjustmentTest {
    private val stroke=MaskStroke(listOf(MaskPoint(.25f,.5f),MaskPoint(.5f,.5f)),.1f,.5f)
    private fun archive(p: Project)=ByteArrayOutputStream().also {ProjectIO.write(p,it)}.toByteArray()
    @Test fun localResourceRoundTripAndLegacyMigration() {
        val p=ProjectIO.create(ByteArray(20){it.toByte()},400,200)
            .withEdit(Edit(local=LocalAdjustment(1f,Region(listOf(stroke)))))
        val bytes=archive(p)
        val entries=mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use {zip->while(true){val e=zip.nextEntry ?: break;entries+=e.name}}
        assertEquals(listOf("project.json","assets/original","assets/local-mask.json"),entries)
        val loaded=ProjectIO.read(bytes.inputStream())
        assertEquals(p.manifest,loaded.manifest);assertArrayEquals(p.original,loaded.original)
        val old=p.copy(manifest=p.manifest.copy(version=2,honorExifColorSpace=false,pipeline="srgb-basic-v2",edit=Edit(exposureEv=.4f)))
        val migrated=ProjectIO.read(archive(old).inputStream())
        assertEquals(6,migrated.manifest.version);assertEquals(old.manifest.edit,migrated.manifest.edit)
        assertThrows(IllegalArgumentException::class.java){p.manifest.copy(version=2,honorExifColorSpace=false,pipeline="srgb-basic-v2").validate()}
    }
    @Test fun missingCorruptAndUnexpectedMaskAreRejected() {
        val p=ProjectIO.create(byteArrayOf(1),400,200).withEdit(Edit(local=LocalAdjustment(1f,Region(listOf(stroke)))))
        val entries=mutableMapOf<String,ByteArray>()
        ZipInputStream(archive(p).inputStream()).use {zip->while(true){val e=zip.nextEntry ?: break;entries[e.name]=zip.readBytes()}}
        fun rejects(payloads: Map<String,ByteArray>) {
            val bytes=ByteArrayOutputStream().also {out->ZipOutputStream(out).use {zip->payloads.forEach {(name,data)->
                zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry()}}}.toByteArray()
            assertThrows(Exception::class.java){ProjectIO.read(bytes.inputStream())}
        }
        rejects(entries-"assets/local-mask.json")
        rejects(entries+ mapOf("assets/local-mask.json" to "{}".encodeToByteArray()))
        rejects(entries+ mapOf("assets/unknown" to byteArrayOf(1)))
    }
    @Test fun unionEraseAndFeatherHaveDefinedCoverage() {
        val region=Region(listOf(stroke));val a=MaskRaster.create(region,400,200)
        assertEquals(255,a.sample(.35f,.5f));assertEquals(0,a.sample(.8f,.5f))
        assertTrue(a.sample(.35f,.575f) in 80..180)
        assertArrayEquals(a.coverage,MaskRaster.create(Region(listOf(stroke,stroke)),400,200).coverage)
        val erased=MaskRaster.create(Region(listOf(stroke,stroke.copy(points=listOf(MaskPoint(.35f,.5f)),erase=true))),400,200)
        assertEquals(0,erased.sample(.35f,.5f));assertEquals(255,erased.sample(.48f,.5f))
    }
    @Test fun geometryRoundTripsThroughEveryTransformAndCrop() {
        for(turn in 0..3) for(horizontal in listOf(false,true)) for(vertical in listOf(false,true)) for(angle in listOf(-30f,0f,21f)) {
            val (w,h)=Geometry.canvasSize(400,200,turn)
            val edit=Edit(turns=turn,flipHorizontal=horizontal,flipVertical=vertical,straighten=angle,crop=Crop(w/8,h/8,w*3/4,h*3/4))
            val transform=SourceGeometry(400,200,edit)
            for(point in listOf(MaskPoint(.4f,.4f),MaskPoint(.5f,.5f),MaskPoint(.6f,.6f))) {
                val out=transform.toOutput(point);val back=requireNotNull(transform.toSource(out.x,out.y))
                assertEquals(point.x,back.x,.00001f);assertEquals(point.y,back.y,.00001f)
            }
            assertNull(transform.toSource(-1f,10f))
        }
    }
    @Test fun boundsAndCancellationProtectRasterWork() {
        assertThrows(IllegalArgumentException::class.java){Region(List(129){stroke}).validate()}
        assertThrows(IllegalArgumentException::class.java){Region(listOf(stroke.copy(points=List(4097){MaskPoint(0f,0f)}))).validate()}
        assertThrows(IllegalArgumentException::class.java){Region(listOf(stroke.copy(radius=Float.NaN))).validate()}
        assertThrows(IllegalArgumentException::class.java){Region(listOf(stroke.copy(points=listOf(MaskPoint(2f,0f))))).validate()}
        assertThrows(IllegalArgumentException::class.java) {
            Edit(local=LocalAdjustment(region=Region(listOf(stroke.copy(points=List(4096) {if(it%2==0) MaskPoint(0f,0f) else MaskPoint(1f,1f)})))))
                .validate(4000,2000)
        }
        val raster=MaskRaster.create(Region(),4000,2000);assertEquals(1024,raster.width);assertEquals(512,raster.height)
        var checks=0
        assertThrows(IllegalStateException::class.java){MaskRaster.create(Region(listOf(stroke)),4000,2000){if(++checks>3) error("cancel")}}
    }
    @Test fun localHistoryAndPresetsRetainIndependentRegion() {
        val local=LocalAdjustment(1f,Region(listOf(stroke)))
        val history=History();history.commit(Edit(local=local));history.commit(Edit(local=local.copy(exposureEv=2f)))
        assertEquals(local,history.undo().local);assertEquals(2f,history.redo().local.exposureEv)
        for(look in Look.entries) assertEquals(local,look.apply(Edit(local=local)).local)
    }
}
