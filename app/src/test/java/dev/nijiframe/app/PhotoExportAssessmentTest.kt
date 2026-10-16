// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import dev.nijiframe.editor.*
import org.junit.Assert.*
import org.junit.Test

class PhotoExportAssessmentTest {
    private val large=ProjectIO.create(byteArrayOf(1,2,3),12288,16384).manifest

    @Test fun fullSizeRefusalSuggestsLargestFittingChoiceWithoutChangingRequest() {
        val settings=ExportSettings()
        val result=assessPhotoExport(large,settings,1536,2048,0,96L*1024*1024)
        assertFalse(result.fits)
        assertEquals(2048,result.suggestedEdge)
        assertFalse(assessPhotoExport(large,settings.copy(maxEdge=4000),1536,2048,0,96L*1024*1024).fits)
        assertNull(settings.maxEdge)
        val reduced=assessPhotoExport(large,settings.copy(maxEdge=result.suggestedEdge),1536,2048,0,96L*1024*1024)
        assertTrue(reduced.fits)
    }

    @Test fun codecScratchCanPreventEvenSmallOutputAndMustNotOfferFalseRemedy() {
        val result=assessPhotoExport(large,ExportSettings(maxEdge=1280),1536,2048,
            large.width.toLong()*large.height*8,96L*1024*1024)
        assertFalse(result.fits)
        assertNull(result.suggestedEdge)
    }

    @Test fun loadedSourceAndDraftGeometryUseActualAdmissionBudget() {
        val small=ProjectIO.create(byteArrayOf(1),900,600).manifest
        val plain=photoExportRequired(small,ExportSettings(),900,600,0)
        val draft=small.copy(edit=Edit(turns=1,crop=Crop(10,20,200,300)))
        val transformed=photoExportRequired(draft,ExportSettings(),900,600,0)
        assertTrue(transformed>plain)
        assertTrue(assessPhotoExport(small,ExportSettings(),900,600,0,plain).fits)
        assertFalse(assessPhotoExport(draft,ExportSettings(),900,600,0,plain).fits)
        assertEquals(plain+900L*600*4,photoExportRequired(small,ExportSettings(),450,300,0))
    }
}
