// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class BaseCoverageTest {
    private val base=BaseCoverage(2,2,"ff800040")
    @Test fun v5MigratesInputPolicyWithoutChangingSourceOrEdits() {
        val edit=Edit(turns=1,local=LocalAdjustment(.5f,Region(base=base),.3f))
        val current=ProjectIO.create(byteArrayOf(1,2,3),100,80).withEdit(edit)
        val legacy=current.copy(manifest=current.manifest.copy(version=5,honorExifColorSpace=false,pipeline="srgb-region-v5"))
        val encoded=ByteArrayOutputStream().also {ProjectIO.write(legacy,it)}.toByteArray()
        val loaded=ProjectIO.read(encoded.inputStream())
        assertEquals(6,loaded.manifest.version);assertEquals("srgb-import-v6",loaded.manifest.pipeline)
        assertEquals(edit,loaded.manifest.edit);assertArrayEquals(legacy.original,loaded.original)
    }
    @Test fun baseThenManualCorrectionRoundTripsAndCanBeUndone() {
        val original=Region(base=base)
        val stroke=MaskStroke(listOf(MaskPoint(.25f,.25f)),.1f,1f,true)
        val corrected=original.copy(strokes=listOf(stroke))
        val raster=MaskRaster.create(corrected,100,100)
        assertEquals(0,raster.sample(.25f,.25f))
        assertEquals(64,raster.sample(.75f,.75f))
        val edit=Edit(local=LocalAdjustment(.8f,corrected,.4f))
        val project=ProjectIO.create(byteArrayOf(1,2,3),100,100).withEdit(edit)
        val encoded=ByteArrayOutputStream().also {ProjectIO.write(project,it)}.toByteArray()
        val reopened=ProjectIO.read(encoded.inputStream())
        assertEquals(6,reopened.manifest.version);assertEquals(edit,reopened.manifest.edit)
        assertArrayEquals(project.original,reopened.original)
        assertArrayEquals(raster.coverage,MaskRaster.create(reopened.manifest.edit.local.region,100,100).coverage)
        val history=History(Edit(local=LocalAdjustment(region=original)))
        history.commit(edit);assertEquals(original,history.undo().local.region);assertEquals(edit,history.redo())
        assertEquals(base,BaseCoverage.from(base.raster()))
        assertFalse(original.isEmpty)
    }
    @Test fun malformedAndLegacyBaseCoverageAreRejected() {
        for(invalid in listOf(BaseCoverage(0,2,""),BaseCoverage(257,1,"00"),base.copy(hex="ff"),
            base.copy(hex="ff80004g"),base.copy(hex="FF800040")))
            assertThrows(IllegalArgumentException::class.java) {invalid.validate()}
        val original=byteArrayOf(1)
        val mask="""{"strokes":[],"base":{"width":2,"height":2,"hex":"ff800040"}}""".toByteArray()
        val manifest="""{"version":4,"pipeline":"srgb-soften-v4","sourceBytes":1,"sourceSha256":"${ProjectIO.digest(original)}","width":10,"height":10,"localMask":{"path":"assets/local-mask.json","bytes":${mask.size},"sha256":"${ProjectIO.digest(mask)}"}}""".toByteArray()
        val encoded=ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { zip ->
            for((name,payload) in listOf("project.json" to manifest,"assets/original" to original,"assets/local-mask.json" to mask)) {
                zip.putNextEntry(ZipEntry(name));zip.write(payload);zip.closeEntry()
            }
        }}.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {ProjectIO.read(encoded.inputStream())}
    }
    @Test fun genuineV4KeepsSofteningAndStrokesWhenMigrated() {
        val region=Region(listOf(MaskStroke(listOf(MaskPoint(.4f,.6f)))))
        val p=ProjectIO.create(byteArrayOf(5),80,60).withEdit(Edit(local=LocalAdjustment(.2f,region,.5f,.9f)))
        val mask="""{"strokes":[{"points":[{"x":0.4,"y":0.6}],"radius":0.06,"hardness":0.5,"erase":false}]}""".toByteArray()
        val manifest="""{"version":4,"pipeline":"srgb-soften-v4","sourceBytes":1,"sourceSha256":"${ProjectIO.digest(p.original)}","width":80,"height":60,"edit":{"local":{"exposureEv":0.2,"soften":0.5,"detailProtection":0.9}},"localMask":{"path":"assets/local-mask.json","bytes":${mask.size},"sha256":"${ProjectIO.digest(mask)}"}}""".toByteArray()
        val bytes=ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            for((name,payload) in listOf("project.json" to manifest,"assets/original" to p.original,"assets/local-mask.json" to mask)) {
                zip.putNextEntry(ZipEntry(name));zip.write(payload);zip.closeEntry()
            }
        }}.toByteArray()
        val migrated=ProjectIO.read(bytes.inputStream())
        assertEquals(p.manifest.copy(honorExifColorSpace=false),migrated.manifest);assertArrayEquals(p.original,migrated.original)
    }
}
