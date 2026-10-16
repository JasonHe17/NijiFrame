// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// Small project-owned line drawings. The containing control supplies its accessible label.
internal enum class Glyph { BACK, CLOSE, CHECK, UNDO, REDO, COMPARE, DETAIL, MORE, SUN, CONTRAST, MOON, COLOR, CROP, ROTATE, BRUSH, FACE, PRESET, SHARPEN, SHIELD }

@Composable
internal fun EditorGlyph(glyph: Glyph, modifier: Modifier=Modifier, color: Color=LocalContentColor.current) {
    Canvas(modifier.size(22.dp)) {
        scale(size.width/24f,size.height/24f,pivot=Offset.Zero) {
            fun line(x:Float,y:Float,x2:Float,y2:Float)=drawLine(color,Offset(x,y),Offset(x2,y2),1.6f,StrokeCap.Round)
            fun circle(x:Float,y:Float,r:Float)=drawCircle(color,r,Offset(x,y),style=Stroke(1.6f))
            when(glyph) {
                Glyph.BACK -> {line(14f,5f,7f,12f);line(7f,12f,14f,19f)}
                Glyph.CLOSE -> {line(6f,6f,18f,18f);line(18f,6f,6f,18f)}
                Glyph.CHECK -> {line(4f,12f,9f,17f);line(9f,17f,20f,6f)}
                Glyph.MORE -> for(x in listOf(5f,12f,19f)) drawCircle(color,1.4f,Offset(x,12f))
                Glyph.SUN -> {
                    circle(12f,12f,4f)
                    for(i in 0..7) {val a=i*Math.PI/4;line(12f+cos(a).toFloat()*7,12f+sin(a).toFloat()*7,12f+cos(a).toFloat()*9.5f,12f+sin(a).toFloat()*9.5f)}
                }
                Glyph.CONTRAST -> {circle(12f,12f,8f);drawArc(color,-90f,180f,true,Offset(4f,4f),Size(16f,16f))}
                Glyph.MOON -> {
                    val p=Path().apply {moveTo(16f,3f);cubicTo(1f,2f,1f,22f,16f,21f);cubicTo(22f,20f,22f,16f,21f,13f);cubicTo(12f,19f,8f,7f,16f,3f)}
                    drawPath(p,color,style=Stroke(1.6f))
                }
                Glyph.COLOR -> {circle(8f,9f,5f);circle(16f,9f,5f);circle(12f,16f,5f)}
                Glyph.CROP -> {line(7f,2f,7f,17f);line(7f,17f,22f,17f);line(2f,7f,17f,7f);line(17f,7f,17f,22f)}
                Glyph.UNDO, Glyph.REDO -> {
                    val flip=if(glyph==Glyph.REDO) -1f else 1f
                    fun x(v:Float)=12+(v-12)*flip
                    val p=Path().apply {moveTo(x(4f),9f);lineTo(x(14f),9f);cubicTo(x(23f),9f,x(23f),20f,x(13f),20f)}
                    drawPath(p,color,style=Stroke(1.6f));line(x(4f),9f,x(9f),4f);line(x(4f),9f,x(9f),14f)
                }
                Glyph.COMPARE -> {drawRect(color,Offset(3f,5f),Size(18f,14f),style=Stroke(1.6f));line(12f,2f,12f,22f)}
                Glyph.DETAIL -> {circle(10f,10f,6f);line(15f,15f,21f,21f);line(7f,10f,13f,10f);line(10f,7f,10f,13f)}
                Glyph.ROTATE -> {drawArc(color,-60f,290f,false,Offset(4f,4f),Size(16f,16f),style=Stroke(1.6f));line(17f,3f,17f,8f);line(17f,8f,22f,8f)}
                Glyph.BRUSH -> {line(8f,15f,18f,4f);line(12f,18f,21f,7f);line(18f,4f,21f,7f);circle(7f,18f,3f)}
                Glyph.FACE -> {drawOval(color,Offset(5f,3f),Size(14f,18f),style=Stroke(1.6f));line(8f,10f,9f,10f);line(15f,10f,16f,10f);drawArc(color,0f,180f,false,Offset(9f,13f),Size(6f,4f),style=Stroke(1.6f))}
                Glyph.PRESET -> {drawRect(color,Offset(3f,4f),Size(14f,14f),style=Stroke(1.6f));line(8f,21f,21f,21f);line(21f,21f,21f,8f)}
                Glyph.SHARPEN -> {line(12f,3f,3f,21f);line(3f,21f,21f,21f);line(21f,21f,12f,3f);line(12f,3f,12f,21f)}
                Glyph.SHIELD -> {val p=Path().apply {moveTo(12f,2f);lineTo(21f,6f);cubicTo(21f,15f,18f,19f,12f,22f);cubicTo(6f,19f,3f,15f,3f,6f);close()};drawPath(p,color,style=Stroke(1.6f))}
            }
        }
    }
}
