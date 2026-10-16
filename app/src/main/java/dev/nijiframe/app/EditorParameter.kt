// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import dev.nijiframe.editor.Edit
import kotlin.math.round

/** Display units are deliberately separate from the persisted image-processing values. */
internal enum class EditorParameter(
    val label: String, val tool: EditorTool, val range: ClosedFloatingPointRange<Float>,
    val unit: String = "", val glyph: Glyph = Glyph.SUN, val ruler: Boolean = true,
    val step: Float = 1f, val fineStep: Float = step
) {
    EXPOSURE("曝光",EditorTool.LIGHT,-3f..3f,"EV",step=.05f,fineStep=.01f),
    CONTRAST("对比度",EditorTool.LIGHT,-100f..100f,glyph=Glyph.CONTRAST),
    HIGHLIGHTS("高光",EditorTool.LIGHT,-100f..100f), SHADOWS("阴影",EditorTool.LIGHT,-100f..100f,glyph=Glyph.MOON),
    WHITES("白色",EditorTool.LIGHT,-100f..100f), BLACKS("黑色",EditorTool.LIGHT,-100f..100f,glyph=Glyph.MOON),
    SATURATION("饱和度",EditorTool.COLOR,-100f..100f,glyph=Glyph.COLOR),
    VIBRANCE("鲜艳度",EditorTool.COLOR,-100f..100f,glyph=Glyph.COLOR),
    WARMTH("色温",EditorTool.COLOR,-100f..100f,glyph=Glyph.COLOR), TINT("色调",EditorTool.COLOR,-100f..100f,glyph=Glyph.COLOR),
    SHARPEN("锐化",EditorTool.DETAILS,0f..200f,"%",Glyph.SHARPEN,false),
    VIGNETTE("暗角",EditorTool.DETAILS,-100f..100f,glyph=Glyph.CONTRAST),
    ANGLE("校正",EditorTool.GEOMETRY,-45f..45f,"°",Glyph.ROTATE,step=.5f,fineStep=.1f),
    LOCAL_EXPOSURE("局部曝光",EditorTool.LOCAL,-3f..3f,"EV",step=.05f,fineStep=.01f),
    LOCAL_WARMTH("局部色温",EditorTool.LOCAL,-100f..100f,glyph=Glyph.COLOR),
    LOCAL_TINT("局部色调",EditorTool.LOCAL,-100f..100f,glyph=Glyph.COLOR),
    LOCAL_SATURATION("局部饱和度",EditorTool.LOCAL,-100f..100f,glyph=Glyph.COLOR),
    SOFTEN("柔肤",EditorTool.LOCAL,0f..100f,"%",Glyph.FACE,false),
    PROTECTION("细节保护",EditorTool.LOCAL,0f..100f,"%",Glyph.SHIELD,false);

    fun get(e: Edit): Float = when(this) {
        EXPOSURE->e.exposureEv;CONTRAST->e.contrast*100;HIGHLIGHTS->e.highlights*100;SHADOWS->e.shadows*100
        WHITES->e.whites*100;BLACKS->e.blacks*100;SATURATION->(e.saturation-1)*100;VIBRANCE->e.vibrance*100
        WARMTH->e.warmth*100;TINT->e.tint*100;SHARPEN->e.sharpen*100;VIGNETTE->e.vignette*100
        ANGLE->e.straighten;LOCAL_EXPOSURE->e.activeLocal.exposureEv;SOFTEN->e.activeLocal.soften*100;PROTECTION->e.activeLocal.detailProtection*100
        LOCAL_WARMTH->e.activeLocal.warmth*100;LOCAL_TINT->e.activeLocal.tint*100;LOCAL_SATURATION->(e.activeLocal.saturation-1)*100
    }
    fun set(e: Edit, value: Float): Edit {
        require(value.isFinite())
        val v=value.coerceIn(range)
        return when(this) {
            EXPOSURE->e.copy(exposureEv=v);CONTRAST->e.copy(contrast=v/100);HIGHLIGHTS->e.copy(highlights=v/100)
            SHADOWS->e.copy(shadows=v/100);WHITES->e.copy(whites=v/100);BLACKS->e.copy(blacks=v/100)
            SATURATION->e.copy(saturation=1+v/100);VIBRANCE->e.copy(vibrance=v/100);WARMTH->e.copy(warmth=v/100)
            TINT->e.copy(tint=v/100);SHARPEN->e.copy(sharpen=v/100);VIGNETTE->e.copy(vignette=v/100)
            ANGLE->e.copy(straighten=v);LOCAL_EXPOSURE->e.withActiveLocal(e.activeLocal.copy(exposureEv=v))
            SOFTEN->e.withActiveLocal(e.activeLocal.copy(soften=v/100));PROTECTION->e.withActiveLocal(e.activeLocal.copy(detailProtection=v/100))
            LOCAL_WARMTH->e.withActiveLocal(e.activeLocal.copy(warmth=v/100))
            LOCAL_TINT->e.withActiveLocal(e.activeLocal.copy(tint=v/100))
            LOCAL_SATURATION->e.withActiveLocal(e.activeLocal.copy(saturation=1+v/100))
        }
    }
    val neutral get()=get(Edit())
    val description get()=when(this) { ANGLE->"校正角度";SOFTEN->"柔肤强度调整";PROTECTION->"柔肤细节保护";else->"${label}调整" }
}

/** Relative motion is clamped on every event, so reversing at an endpoint has no dead travel. */
internal class RulerMotion(initial: Float, private val range: ClosedFloatingPointRange<Float>,
                           private val step: Float, private val pixelsPerStep: Float) {
    private var raw=initial
    private var anchor=initial
    fun move(delta: Float): Float {
        raw=(raw-delta/pixelsPerStep*step).coerceIn(range)
        val next=(anchor+round((raw-anchor)/step)*step).coerceIn(range)
        if(raw==range.start || raw==range.endInclusive) anchor=raw
        return next
    }
}

internal fun parseParameter(text: String, range: ClosedFloatingPointRange<Float>, step: Float): Float? {
    val raw=text.trim().replace('−','-').replace(',','.')
    if(!Regex("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)").matches(raw)) return null
    val value=raw.toFloatOrNull()?.takeIf {it.isFinite() && it in range} ?: return null
    return (round(value/step)*step).coerceIn(range)
}
