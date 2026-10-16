// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalRegionsTest {
    private val first=LocalAdjustment(.5f,Region(listOf(MaskStroke(listOf(MaskPoint(.2f,.4f))))),.3f)
    private fun archive(project: Project)=ByteArrayOutputStream().also {ProjectIO.write(project,it)}.toByteArray()
    private fun raw(manifest: String, source: ByteArray, mask: String)=ByteArrayOutputStream().also {out->
        ZipOutputStream(out).use {zip->
            for((name,bytes) in listOf("project.json" to manifest.toByteArray(),"assets/original" to source,"assets/local-mask.json" to mask.toByteArray())) {
                zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()
            }
        }
    }.toByteArray()
    @Test fun genuineV6PayloadKeepsValuesAndNewResourceSemanticsCannotHideUnderOldVersion() {
        val source=byteArrayOf(1,2,3)
        val oldMask="""{"strokes":[{"points":[{"x":0.2,"y":0.4}],"radius":0.06,"hardness":0.5,"erase":false}],"base":null}"""
        fun manifest(version: Int,pipeline: String,mask: String)= """{"version":$version,"pipeline":"$pipeline","sourceBytes":3,"sourceSha256":"${ProjectIO.digest(source)}","width":100,"height":80,"honorExifColorSpace":false,"edit":{"warmth":0.1,"local":{"exposureEv":0.5,"soften":0.3}},"localMask":{"bytes":${mask.toByteArray().size},"sha256":"${ProjectIO.digest(mask.toByteArray())}"}}"""
        val restored=ProjectIO.read(raw(manifest(6,"srgb-import-v6",oldMask),source,oldMask).inputStream())
        assertEquals(7,restored.manifest.version);assertFalse(restored.manifest.honorExifColorSpace)
        assertEquals(first,restored.manifest.edit.local);assertEquals(.1f,restored.manifest.edit.warmth,0f)
        val forbidden="""{"refinements":[{"points":[{"x":0.5,"y":0.5}],"radius":0.0001}]}"""
        assertThrows(IllegalArgumentException::class.java) {ProjectIO.read(raw(manifest(6,"srgb-import-v6",forbidden),source,forbidden).inputStream())}
        val unknown="""{"regions":{"unknown":{"base":{"width":1,"height":1,"hex":"ff"}}}}"""
        assertThrows(IllegalArgumentException::class.java) {ProjectIO.read(raw(manifest(7,"srgb-regions-v7",unknown),source,unknown).inputStream())}
    }
    @Test fun exportAdmissionAccountsForAdditionalEnabledMasks() {
        val single=Manifest(sourceBytes=1,sourceSha256="0".repeat(64),width=4000,height=3000,edit=Edit(local=first))
        var edit=single.edit
        repeat(3) {edit=edit.addRegion("r$it","Region $it").withActiveLocal(first)}
        val multiple=single.copy(edit=edit)
        val one=ImageSizing.exportWorkingBytes(single,1,true)
        val four=ImageSizing.exportWorkingBytes(multiple,1,true)
        assertTrue("Additional live mask storage must affect admission",four>one+3*1024*1024)
        val off=multiple.copy(edit=edit.copy(regions=edit.regions.map {it.copy(enabled=false)}))
        assertEquals(one,ImageSizing.exportWorkingBytes(off,1,true))
    }
    @Test fun independentRegionLifecycleAndHistoryPreserveOtherRegions() {
        val original=Edit(local=first)
        val two=original.addRegion("clothes","衣服").withActiveLocal(LocalAdjustment(warmth=-.4f,tint=.2f,saturation=.7f))
        val three=two.addRegion("background","背景").withActiveLocal(LocalAdjustment(exposureEv=-1f))
        three.validate(1000,800)
        assertEquals(first,three.regions.first().adjustment)
        assertEquals(two.activeLocal,three.regions[1].adjustment)
        val selected=three.selectRegion("clothes")
        assertEquals(three.regions,selected.regions)
        val reset=selected.withActiveLocal(LocalAdjustment(region=selected.activeLocal.region))
        assertEquals(three.regions[0],reset.regions[0]);assertEquals(three.regions[2],reset.regions[2])
        val history=History(three);history.commit(reset)
        assertEquals(three,history.undo());assertEquals(reset,history.redo())
        val hidden=selected.changeActiveRegion {it.copy(enabled=false,name="衣料")}
        assertFalse(hidden.regions[1].enabled);assertEquals("衣料",hidden.regions[1].name)
        assertEquals(listOf(three.regions[0],three.regions[2]),hidden.deleteActiveRegion().regions)
        assertEquals(Edit(),original.deleteActiveRegion())
        assertTrue(selected.withoutLocalEffects().localRegions.all {!it.adjustment.hasEffect})
    }
    @Test fun selectingARegionDoesNotConsumeUndoOrDiscardRedo() {
        val initial=Edit(local=first).addRegion("second","第二区")
        val history=History(initial)
        history.commit(initial.selectRegion("legacy"))
        assertFalse(history.canUndo)
        history.commit(history.current.withActiveLocal(first.copy(warmth=.3f)))
        history.undo();assertTrue(history.canRedo)
        history.commit(history.current.selectRegion("second"))
        assertTrue(history.canRedo);assertFalse(history.canUndo)
        assertEquals(.3f,history.redo().regions.first().adjustment.warmth,0f)
    }
    @Test fun v7ArchiveRestoresEveryMaskAndSelectionWithoutChangingOriginal() {
        val fine=Region(refinements=listOf(MaskStroke(listOf(MaskPoint(.501f,.503f)),.0001f,.6f)))
        val edit=Edit(local=first).addRegion("clothes","衣服").withActiveLocal(LocalAdjustment(region=fine,warmth=.2f))
            .addRegion("background","背景").changeActiveRegion {it.copy(enabled=false)}.selectRegion("clothes")
        val p=ProjectIO.create(byteArrayOf(1,3,5,7),12000,8000).withEdit(edit)
        val restored=ProjectIO.read(archive(p).inputStream())
        assertEquals(7,restored.manifest.version);assertEquals(edit,restored.manifest.edit)
        assertArrayEquals(p.original,restored.original)
        assertThrows(IllegalArgumentException::class.java) {p.manifest.copy(version=6,pipeline="srgb-import-v6").validate()}
    }
    @Test fun limitsAndFailedSaveLeavePreviousProjectIntact() {
        var edit=Edit()
        repeat(LocalRegion.MAX_REGIONS-1) {edit=edit.addRegion("r$it","区域$it")}
        assertThrows(IllegalArgumentException::class.java) {edit.addRegion("overflow","超限")}
        assertThrows(IllegalArgumentException::class.java) {edit.copy(regions=edit.regions+edit.regions.first()).validate(100,100)}
        assertThrows(IllegalArgumentException::class.java) {edit.copy(selectedRegionId="missing").validate(100,100)}
        assertThrows(IllegalArgumentException::class.java) {edit.changeActiveRegion {it.copy(name=" ")}.validate(100,100)}
        val base=BaseCoverage(256,256,"ff".repeat(256*256))
        val tooLarge=edit.copy(regions=edit.regions.map {it.copy(adjustment=it.adjustment.copy(region=Region(base=base)))})
        assertThrows(IllegalArgumentException::class.java) {ProjectIO.validateLocalResources(tooLarge)}
        val directory=Files.createTempDirectory("region-atomic").toFile()
        try {
            val file=File(directory,"project.nijiproj");val p=ProjectIO.create(byteArrayOf(1),100,100)
            ProjectIO.saveAtomic(p,file);val before=file.readBytes()
            assertThrows(IllegalArgumentException::class.java) {ProjectIO.saveAtomic(p.withEdit(tooLarge),file)}
            assertArrayEquals(before,file.readBytes());assertEquals(p.manifest,ProjectIO.read(file).manifest)
        } finally {directory.deleteRecursively()}
    }
    @Test fun sourcePixelCorrectionsSurviveLargeSourceAndWindowPartitioning() {
        val width=12000;val height=8000
        val point=MaskPoint(6000.5f/width,4000.5f/height)
        val add=MaskStroke(listOf(point),.75f/height,1f)
        val region=Region(refinements=listOf(add))
        val mask=RegionCoverage(region,width,height)
        val window=mask.window(5998,3998,6,6)
        assertEquals(255,window[2*6+2].toInt() and 255)
        assertEquals(0,window[2*6+3].toInt() and 255)
        val bottom=mask.window(5998,4001,6,3)
        assertArrayEquals(window.copyOfRange(18,36),bottom)
        for(y in 0..5) for(x in 0..5)
            assertEquals(window[y*6+x].toInt() and 255,mask.sample((5998+x+.5f)/width,(3998+y+.5f)/height))
        val erased=RegionCoverage(region.copy(refinements=listOf(add,add.copy(erase=true))),width,height)
        assertEquals(0,erased.window(6000,4000,1,1)[0].toInt())
        assertTrue(Region(strokes=emptyList(),refinements=listOf(add)).isEmpty.not())
        assertThrows(IllegalArgumentException::class.java) {MaskRaster.create(region,width,height)}
    }
    @Test fun refinementDoesNotReinterpretLegacyStrokesAndCanProtectFineDetail() {
        val region=first.region
        val legacy=MaskRaster.create(region,4000,3000)
        val original=RegionCoverage(region,4000,3000)
        for(y in 0..40) for(x in 0..40) assertEquals(legacy.sample(x/40f,y/40f),original.sample(x/40f,y/40f))
        val eye=MaskPoint(.2f,.4f)
        val protected=region.copy(refinements=listOf(MaskStroke(listOf(eye),1f/3000,1f,true)))
        val corrected=RegionCoverage(protected,4000,3000)
        assertEquals(0,corrected.sample(eye.x,eye.y));assertEquals(255,original.sample(eye.x,eye.y))
        assertEquals(original.sample(.21f,.4f),corrected.sample(.21f,.4f))
    }
}
