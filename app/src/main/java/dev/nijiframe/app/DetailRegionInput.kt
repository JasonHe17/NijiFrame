// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nijiframe.editor.*
import kotlin.math.*

@Composable
internal fun DetailRegionInput(ui: EditorUi,editor: EditorViewModel) {
    val detail=requireNotNull(ui.detail)
    val bounds=detail.bounds ?: return
    val manifest=requireNotNull(ui.project).manifest
    val edit=requireNotNull(ui.draft)
    val mapping=remember(manifest.width,manifest.height,edit) {SourceGeometry(manifest.width,manifest.height,edit)}
    var size by remember {mutableStateOf(IntSize.Zero)}
    var pending by remember(detail) {mutableStateOf<List<MaskPoint>>(emptyList())}
    // ContentScale.None centers the native-sized frame at an integer screen offset.
    val left=((size.width-bounds.width())/2f).roundToInt()
    val top=((size.height-bounds.height())/2f).roundToInt()
    Canvas(Modifier.fillMaxSize().onSizeChanged {size=it}.semantics {contentDescription="细节区域画布"}
        .pointerInput(detail,size,ui.regionMode,ui.brushRadius,ui.brushHardness) {
            fun convert(p: Offset): MaskPoint? {
                val x=p.x-left;val y=p.y-top
                if(x<0 || y<0 || x>=bounds.width() || y>=bounds.height()) return null
                return mapping.toSource(bounds.left+x,bounds.top+y)
            }
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false)
                val start=convert(down.position)
                if(start!=null) {
                    down.consume();pending=listOf(start)
                    var cancelled=false
                    try {
                        while(true) {
                            val event=awaitPointerEvent()
                            if(event.changes.count {it.pressed}>1) {cancelled=true;break}
                            val change=event.changes.firstOrNull {it.id==down.id}
                            if(change==null) {cancelled=true;break}
                            val point=convert(change.position)
                            // Leaving the frame cancels this gesture instead of joining across unseen pixels.
                            if(point==null) {cancelled=true;break}
                            val last=pending.last()
                            if(pending.size<Region.MAX_POINTS &&
                                (hypot((point.x-last.x)*manifest.width,(point.y-last.y)*manifest.height)>=
                                    max(1f,ui.brushRadius*min(manifest.width,manifest.height)*.1f) || !change.pressed))
                                pending=pending+point
                            change.consume()
                            if(!change.pressed) break
                        }
                        if(!cancelled) editor.addDetailStroke(pending,detail)
                    } finally {pending=emptyList()}
                }
            }
        }) {
        fun screen(point: MaskPoint): Offset {
            val p=mapping.toOutput(point)
            return Offset(left+p.x-bounds.left,top+p.y-bounds.top)
        }
        for(i in pending.indices) {
            val p=screen(pending[i]);val previous=screen(pending[max(0,i-1)])
            drawLine(if(ui.regionMode==RegionMode.ERASE) Color.Cyan else Color(0xffff4060),previous,p,3.dp.toPx())
            drawCircle(Color.White,2.dp.toPx(),p)
        }
    }
}
