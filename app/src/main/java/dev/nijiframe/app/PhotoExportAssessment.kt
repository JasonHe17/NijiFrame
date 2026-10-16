// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import dev.nijiframe.editor.ExportSettings
import dev.nijiframe.editor.ImageSizing
import dev.nijiframe.editor.Manifest

/** Uses the same buffer and codec budget as admission at the actual export boundary. */
internal data class PhotoExportAssessment(val required: Long, val available: Long, val suggestedEdge: Int?) {
    val fits get() = required <= available
}

internal fun photoExportRequired(manifest: Manifest, settings: ExportSettings, sourceWidth: Int,
                                sourceHeight: Int, codecHeadroom: Long): Long {
    val sample=ImageSizing.exportSample(manifest,settings)
    val reusable=sourceWidth==manifest.width && sourceHeight==manifest.height && sample==1
    return ImageSizing.exportWorkingBytes(manifest,sample,reusable,settings.maxEdge!=null)+
        if(reusable) 0 else codecHeadroom
}

internal fun assessPhotoExport(manifest: Manifest, settings: ExportSettings, sourceWidth: Int,
                              sourceHeight: Int, codecHeadroom: Long, available: Long): PhotoExportAssessment {
    fun required(candidate: ExportSettings)=photoExportRequired(manifest,candidate,sourceWidth,sourceHeight,codecHeadroom)
    val required=required(settings)
    val currentEdge=settings.maxEdge
    val suggested=if(required<=available) null else listOf(4000,2048,1280).firstOrNull { edge ->
        (currentEdge==null || edge<currentEdge) && required(settings.copy(maxEdge=edge))<=available
    }
    return PhotoExportAssessment(required,available,suggested)
}
