// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ImageSizingTest {
    @Test fun largeProjectsKeepOriginalDimensionsAndCropAcrossRoundTrip() {
        for ((w,h) in listOf(4000 to 3000, 6000 to 4000, 8000 to 6000, 10000 to 10000,
            16320 to 12240, 16384 to 12288, 21000 to 10000)) {
            val original = byteArrayOf(1,2,3)
            val edit = Edit(turns=1, crop=Crop(20,30,h-40,w-60))
            val project = ProjectIO.create(original,w,h).withEdit(edit)
            val archive = ByteArrayOutputStream().also { ProjectIO.write(project,it) }
            val restored = ProjectIO.read(archive.toByteArray().inputStream())
            assertEquals(project.manifest,restored.manifest)
            assertArrayEquals(original,restored.original)
            val sample = ImageSizing.previewSample(w,h)
            assertTrue(sample>1)
            assertTrue(maxOf(ImageSizing.ceilDiv(w,sample),ImageSizing.ceilDiv(h,sample))<=2048)
        }
        assertThrows(IllegalArgumentException::class.java) { ProjectIO.create(byteArrayOf(1),21001,10000) }
        assertThrows(IllegalArgumentException::class.java) { ProjectIO.create(byteArrayOf(1),32769,1) }
    }

    @Test fun twoHundredMegapixelExportBudgetsStayLongAndRespectTightCrops() {
        val m = ProjectIO.create(byteArrayOf(1),16384,12288).manifest
        assertEquals(8,ImageSizing.previewSample(m.width,m.height))
        assertEquals(4,ImageSizing.exportSample(m,ExportSettings(maxEdge=4000)))
        assertEquals(1,ImageSizing.exportSample(m,ExportSettings()))
        val edited=m.copy(edit=Edit(turns=1))
        assertTrue(ImageSizing.exportWorkingBytes(edited,1,false)>Int.MAX_VALUE.toLong())
        val tight=m.copy(edit=Edit(crop=Crop(8000,6000,256,256)))
        assertEquals(1,ImageSizing.exportSample(tight,ExportSettings(maxEdge=1280)))
        assertTrue(ImageSizing.exportWorkingBytes(tight,1,false)>Int.MAX_VALUE.toLong())
    }

    @Test fun exportPlanningAccountsForCropAndNeverReusesThePreviewForFullOutput() {
        val m = ProjectIO.create(byteArrayOf(1),8000,6000).manifest
        assertEquals(1,ImageSizing.exportSample(m,ExportSettings()))
        assertEquals(2,ImageSizing.exportSample(m,ExportSettings(maxEdge=4000)))
        assertEquals(2,ImageSizing.exportSample(m,ExportSettings(maxEdge=2048)))
        assertEquals(4,ImageSizing.exportSample(m,ExportSettings(maxEdge=1280)))
        val cropped=m.copy(edit=Edit(crop=Crop(0,0,1000,1000)))
        assertEquals(1,ImageSizing.exportSample(cropped,ExportSettings(maxEdge=2048)))
        assertTrue(ImageSizing.exportWorkingBytes(m,1,false)>384_000_000)
        assertTrue(ImageSizing.exportWorkingBytes(m,4,false,true)<64L*1024*1024)
    }

    @Test fun boundedReadingHandlesChunkBoundariesAndShortReads() {
        val bytes=ByteArray(131073) { (it%251).toByte() }
        val shortReads=object: ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int)=super.read(b,off,minOf(7,len))
        }
        assertArrayEquals(bytes,ProjectIO.readBounded(shortReads,bytes.size))
        assertThrows(IllegalArgumentException::class.java) { ProjectIO.readBounded(bytes.inputStream(),bytes.size-1) }
        assertArrayEquals(byteArrayOf(),ProjectIO.readBounded(byteArrayOf().inputStream(),0))
    }
}
