// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun EditorActions(ui: EditorUi,editor: EditorViewModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=4.dp)) {
        val draft=ui.tool!=null
        IconButton(onClick={if(draft) editor.localUndo() else editor.undo()},
            enabled=!ui.busy && !ui.comparing && if(draft) ui.localCanUndo else ui.canUndo,
            modifier=Modifier.semantics {contentDescription="撤销";stateDescription=if(draft) "本次工具调整" else "已完成的工具"}) {EditorGlyph(Glyph.UNDO)}
        IconButton(onClick={if(draft) editor.localRedo() else editor.redo()},
            enabled=!ui.busy && !ui.comparing && if(draft) ui.localCanRedo else ui.canRedo,
            modifier=Modifier.semantics {contentDescription="重做";stateDescription=if(draft) "本次工具调整" else "已完成的工具"}) {EditorGlyph(Glyph.REDO)}
        Spacer(Modifier.weight(1f))
        TextButton(onClick=editor::compare,enabled=!ui.busy,modifier=Modifier.semantics {
            contentDescription=if(ui.comparing) "返回调整" else "对比原图";selected=ui.comparing
        }) {EditorGlyph(Glyph.COMPARE);Spacer(Modifier.width(4.dp));Text("对比",style=MaterialTheme.typography.labelMedium)}
        TextButton(onClick=editor::openDetail,enabled=!ui.busy && !ui.rendering && ui.preview!=null,
            modifier=Modifier.semantics {contentDescription="查看细节"}) {
            EditorGlyph(Glyph.DETAIL);Spacer(Modifier.width(4.dp));Text("细节",style=MaterialTheme.typography.labelMedium)
        }
        IconButton(onClick=editor::fitCanvas,modifier=Modifier.semantics {contentDescription="适应画布"}) {EditorGlyph(Glyph.CROP)}
    }
}
