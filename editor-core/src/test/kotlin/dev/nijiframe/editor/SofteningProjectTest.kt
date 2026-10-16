// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.*

class SofteningProjectTest {
    private fun archive(entries: List<Pair<String,ByteArray>>)=ByteArrayOutputStream().also {out->ZipOutputStream(out).use {zip->
        entries.forEach {(name,bytes)->zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}
    }}.toByteArray()
    @Test fun genuineV3RegionMigratesWithoutEnablingSoftening() {
        val source=byteArrayOf(1,2)
        val region="""{"strokes":[{"points":[{"x":0.5,"y":0.5}],"radius":0.1,"hardness":0.5,"erase":false}]}""".encodeToByteArray()
        val manifest="""{"version":3,"pipeline":"srgb-local-v3","source":"assets/original","sourceBytes":2,
            "sourceSha256":"${ProjectIO.digest(source)}","width":64,"height":48,
            "edit":{"local":{"exposureEv":0.5}},"localMask":{"path":"assets/local-mask.json","bytes":${region.size},"sha256":"${ProjectIO.digest(region)}"}}""".encodeToByteArray()
        val result=ProjectIO.read(archive(listOf("project.json" to manifest,"assets/original" to source,"assets/local-mask.json" to region)).inputStream())
        assertEquals(7,result.manifest.version);assertEquals(.5f,result.manifest.edit.local.exposureEv)
        assertEquals(0f,result.manifest.edit.local.soften);assertEquals(.75f,result.manifest.edit.local.detailProtection)
        assertEquals(1,result.manifest.edit.local.region.strokes.size);assertArrayEquals(source,result.original)
    }
    @Test fun softeningRoundTripsAndLegacyVersionsRejectIt() {
        val local=LocalAdjustment(.4f,Region(listOf(MaskStroke(listOf(MaskPoint(.5f,.5f))))),.6f,.8f)
        val p=ProjectIO.create(byteArrayOf(1),64,48).withEdit(Edit(local=local))
        val bytes=ByteArrayOutputStream().also {ProjectIO.write(p,it)}.toByteArray()
        assertEquals(p.manifest,ProjectIO.read(bytes.inputStream()).manifest)
        assertThrows(IllegalArgumentException::class.java){p.manifest.copy(version=3,pipeline="srgb-local-v3").validate()}
        assertThrows(IllegalArgumentException::class.java){local.copy(soften=Float.NaN).validate()}
        assertThrows(IllegalArgumentException::class.java){local.copy(detailProtection=2f).validate()}
        for(look in Look.entries) assertEquals(local,look.apply(Edit(local=local)).local)
        val h=History();h.commit(Edit(local=local));assertEquals(Edit(),h.undo());assertEquals(local,h.redo().local)
    }
}
