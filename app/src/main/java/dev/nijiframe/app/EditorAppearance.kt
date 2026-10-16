// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class AppAppearance(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
enum class CanvasBackground(val label: String) { AUTO("自动"), LIGHT("浅灰"), MID("中灰"), DARK("深灰") }

internal val LocalEditorCanvas = staticCompositionLocalOf { Color(0xffebebeb) }
internal val LocalEditorDark = staticCompositionLocalOf { false }

@Composable
internal fun NijiTheme(appearance: AppAppearance, canvas: CanvasBackground, content: @Composable () -> Unit) {
    val requestedDark = when(appearance) {
        AppAppearance.SYSTEM -> isSystemInDarkTheme()
        AppAppearance.LIGHT -> false
        AppAppearance.DARK -> true
    }
    var touching by remember {mutableStateOf(false)}
    var settledDark by remember {mutableStateOf(requestedDark)}
    val dark=if(touching) settledDark else requestedDark
    SideEffect {if(!touching) settledDark=requestedDark}
    val colors = if(dark) darkColorScheme(
        primary=Color(0xffeeeeeb), onPrimary=Color(0xff202020), primaryContainer=Color(0xff30302e),
        onPrimaryContainer=Color(0xffefefec), secondary=Color(0xffb3b3ae), onSecondary=Color(0xff202020),
        secondaryContainer=Color(0xff30302e), onSecondaryContainer=Color(0xffefefec),
        background=Color(0xff141414), onBackground=Color(0xffefefec),
        surface=Color(0xff1b1b1b), onSurface=Color(0xffefefec), surfaceVariant=Color(0xff30302e),
        onSurfaceVariant=Color(0xffb3b3ae), outline=Color(0xff8c8c86), outlineVariant=Color(0xff3e3e3b),
        surfaceContainer=Color(0xff242424), surfaceContainerHigh=Color(0xff30302e),
        surfaceContainerLow=Color(0xff202020), surfaceContainerHighest=Color(0xff383836),
        surfaceTint=Color.Transparent, error=Color(0xffffb4a9)
    ) else lightColorScheme(
        primary=Color(0xff282828), onPrimary=Color.White, primaryContainer=Color(0xffefefeb),
        onPrimaryContainer=Color(0xff292929), secondary=Color(0xff666666), onSecondary=Color.White,
        secondaryContainer=Color(0xffefefeb), onSecondaryContainer=Color(0xff292929),
        background=Color(0xfff7f7f4), onBackground=Color(0xff292929),
        surface=Color.White, onSurface=Color(0xff292929), surfaceVariant=Color(0xffefefeb),
        onSurfaceVariant=Color(0xff666666), outline=Color(0xff7d7d78), outlineVariant=Color(0xffd9d9d5),
        surfaceContainer=Color(0xfff7f7f4), surfaceContainerHigh=Color(0xffefefeb),
        surfaceContainerLow=Color(0xfffafaf8), surfaceContainerHighest=Color(0xffe9e9e5),
        surfaceTint=Color.Transparent, error=Color(0xffb42318)
    )
    val background=when(canvas) {
        CanvasBackground.AUTO -> if(dark) Color(0xff141414) else Color(0xffebebeb)
        CanvasBackground.LIGHT -> Color(0xffebebeb)
        CanvasBackground.MID -> Color(0xff777777)
        CanvasBackground.DARK -> Color(0xff141414)
    }
    CompositionLocalProvider(LocalEditorCanvas provides background, LocalEditorDark provides dark) {
        MaterialTheme(colorScheme=colors) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                try {
                    awaitPointerEventScope {
                        while(true) touching=awaitPointerEvent().changes.any {it.pressed}
                    }
                } finally {touching=false}
            }) {content()}
        }
    }
}

@Composable
internal fun AppearanceDialog(ui: EditorUi, editor: EditorViewModel, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("外观与查看")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("界面主题",style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                AppAppearance.entries.forEach { value ->
                    FilterChip(selected=ui.appearance==value,onClick={editor.setAppearance(value)},label={Text(value.label)})
                }
            }
            Text("画布背景",style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                CanvasBackground.entries.forEach { value ->
                    FilterChip(selected=ui.canvasBackground==value,onClick={editor.setCanvasBackground(value)},label={Text(value.label)})
                }
            }
            Text("只改变界面和照片周围的背景，不影响照片、调整和工程。",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick=onDismiss){Text("完成")}})
}
