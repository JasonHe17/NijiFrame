// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nijiframe.editor.Crop
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val editor: EditorViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { EditorScreen(editor) } }
    }
}

@Composable
private fun EditorScreen(editor: EditorViewModel) {
    val ui by editor.ui.collectAsStateWithLifecycle()
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(editor::importPhoto) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(editor::open) }
    val projectExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { it?.let(editor::exportProject) }
    val photoExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { it?.let(editor::exportPhoto) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun replace(action: () -> Unit) { if (ui.dirty) pending = action else action() }
    var showExport by remember { mutableStateOf(false) }
    val project = ui.project
    val edit = project?.manifest?.edit
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("NijiFrame", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (project != null) Text(if (ui.dirty) "未保存" else "已保存", style = MaterialTheme.typography.labelLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !ui.busy, onClick = { replace { import.launch(arrayOf("image/jpeg", "image/png")) } }) { Text("导入照片") }
                OutlinedButton(enabled = !ui.busy, onClick = { replace { open.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) } }) { Text("打开工程") }
            }
            if (project == null) {
                Text("在设备上调整照片，保存工程后可继续编辑。")
                Text("支持 sRGB JPEG 和 8 位不透明 PNG，当前限制 800 万像素、32 MiB。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !ui.busy, onClick = editor::restore) { Text("重开已存工程") }
            } else {
                var zoom by remember(project.manifest.sourceSha256) { mutableFloatStateOf(1f) }
                var offset by remember(project.manifest.sourceSha256) { mutableStateOf(Offset.Zero) }
                val transform = rememberTransformableState { scale, pan, _ ->
                    zoom = (zoom * scale).coerceIn(1f, 6f)
                    offset = if (zoom == 1f) Offset.Zero else Offset(
                        (offset.x + pan.x).coerceIn(-1200f, 1200f), (offset.y + pan.y).coerceIn(-1200f, 1200f))
                }
                Box(Modifier.fillMaxWidth().height(280.dp).clipToBounds().background(Color(0xff17191d))
                    .transformable(transform), contentAlignment = Alignment.Center) {
                    ui.preview?.let { bitmap -> Image(bitmap.asImageBitmap(), "编辑预览", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom;
                            translationX = offset.x; translationY = offset.y }) }
                    if (ui.rendering) CircularProgressIndicator(Modifier.size(28.dp))
                }
                val crop = edit?.crop
                val width = crop?.width ?: project.manifest.width
                val height = crop?.height ?: project.manifest.height
                val rotated = (edit?.turns ?: 0) % 2 == 1
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (rotated) height else width} × ${if (rotated) width else height} · sRGB",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { zoom = 1f; offset = Offset.Zero }) { Text("适应画布") }
                }
                if (edit != null) {
                    Adjustment("曝光", edit.exposureEv, -3f..3f, "EV", !ui.busy, { editor.previewEdit(edit.copy(exposureEv = it)) }) { editor.commit(edit.copy(exposureEv = it)) }
                    Adjustment("饱和度", edit.saturation, 0f..2f, "", !ui.busy, { editor.previewEdit(edit.copy(saturation = it)) }) { editor.commit(edit.copy(saturation = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(enabled = !ui.busy, onClick = { editor.commit(edit.copy(turns = (edit.turns + 1) % 4)) }) { Text("旋转 90°") }
                        OutlinedButton(enabled = !ui.busy, onClick = {
                            val m = project.manifest
                            val size = minOf(m.width, m.height)
                            editor.commit(edit.copy(crop = if (edit.crop != null) null else Crop((m.width - size) / 2, (m.height - size) / 2, size, size)))
                        }) { Text(if (edit.crop == null) "居中方形裁剪" else "取消裁剪") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(enabled = ui.canUndo && !ui.busy, onClick = editor::undo) { Text("撤销") }
                        TextButton(enabled = ui.canRedo && !ui.busy, onClick = editor::redo) { Text("重做") }
                        TextButton(enabled = !ui.busy, onClick = editor::compare) { Text(if (ui.comparing) "返回调整" else "对比原图") }
                        TextButton(enabled = !ui.busy, onClick = { editor.commit(dev.nijiframe.editor.Edit()) }) { Text("重置调整") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !ui.busy, onClick = editor::save) { Text("保存工程") }
                    OutlinedButton(enabled = !ui.busy, onClick = { showExport = true }) { Text("导出") }
                    TextButton(enabled = !ui.busy, onClick = { replace(editor::restore) }) { Text("重开") }
                }
                Text("保存工程供本应用恢复；导出工程可备份原图和参数。", style = MaterialTheme.typography.bodySmall)
            }
            Text(ui.status, style = MaterialTheme.typography.bodyMedium)
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
        }
    }
    if (showExport) AlertDialog(onDismissRequest = { showExport = false }, title = { Text("导出") },
        text = { Text("照片：完整尺寸 JPEG，质量 95，不复制位置等元数据。\n工程：包含原图及其元数据、可编辑参数；分享前请确认内容。") },
        confirmButton = { TextButton(onClick = { showExport = false; photoExport.launch("NijiFrame.jpg") }) { Text("导出照片") } },
        dismissButton = { TextButton(onClick = { showExport = false; projectExport.launch("NijiFrame.nijiproj") }) { Text("导出工程") } })
    if (pending != null) AlertDialog(onDismissRequest = { pending = null }, title = { Text("当前修改尚未保存") },
        text = { Text("继续将离开当前编辑。可以取消并先保存工程。") },
        confirmButton = { TextButton(onClick = { val action = pending; pending = null; action?.invoke() }) { Text("继续") } },
        dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } })
    ui.error?.let { error -> AlertDialog(onDismissRequest = editor::dismissError, title = { Text("操作未完成") },
        text = { Text(error) }, confirmButton = { TextButton(onClick = editor::dismissError) { Text("知道了") } }) }
}

@Composable
private fun Adjustment(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String,
                       enabled: Boolean, preview: (Float) -> Unit, commit: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Text("$label ${String.format(Locale.ROOT, "%.2f", draft)} $unit")
    Slider(modifier = Modifier.semantics { contentDescription = label }, value = draft, onValueChange = { draft = it; preview(it) }, onValueChangeFinished = { commit(draft) },
        valueRange = range, enabled = enabled)
}
