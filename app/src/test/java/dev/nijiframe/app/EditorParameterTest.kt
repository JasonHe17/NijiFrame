// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import dev.nijiframe.editor.Edit
import org.junit.Assert.*
import org.junit.Test

class EditorParameterTest {
    @Test fun signedDisplayUnitsPreserveEngineSemanticsAndUnrelatedParameters() {
        val original=Edit(exposureEv=.7f,saturation=1.3f,sharpen=1.8f)
        assertEquals(30f,EditorParameter.SATURATION.get(original),.0001f)
        val edited=EditorParameter.SATURATION.set(original,-100f)
        assertEquals(0f,edited.saturation,0f)
        assertEquals(original.copy(saturation=0f),edited)
        assertEquals(2f,EditorParameter.SHARPEN.set(original,200f).sharpen,0f)
        for(parameter in EditorParameter.entries) {
            for(value in listOf(parameter.range.start,parameter.neutral,parameter.range.endInclusive)) {
                assertEquals(parameter.name,value,parameter.get(parameter.set(original,value)),.0001f)
            }
        }
    }
    @Test fun rulerUsesRelativeMotionAndReversesImmediatelyAtBothLimits() {
        val ruler=RulerMotion(.35f,-3f..3f,.05f,8f)
        assertEquals(.35f,ruler.move(0f),.0001f)
        assertEquals(.4f,ruler.move(-8f),.0001f)
        assertEquals(3f,ruler.move(-10000f),.0001f)
        assertEquals(2.95f,ruler.move(8f),.0001f)
        assertEquals(-3f,ruler.move(10000f),.0001f)
        assertEquals(-2.95f,ruler.move(-8f),.0001f)
    }
    @Test fun fineMotionAccumulatesSubstepsWithoutDrift() {
        val ruler=RulerMotion(.35f,-3f..3f,.01f,8f)
        repeat(8) {ruler.move(-1f)}
        assertEquals(.36f,ruler.move(0f),.0001f)
        repeat(8) {ruler.move(1f)}
        assertEquals(.35f,ruler.move(0f),.0001f)
    }
    @Test fun exactInputRejectsInvalidAndOutOfRangeValuesWithoutSilentlyClipping() {
        for(input in listOf("NaN","Infinity","1e2","3.01","-3.01","1..2","","--1")) {
            assertNull(input,parseParameter(input,-3f..3f,.01f))
        }
        assertEquals(-.35f,parseParameter(" −0,35 ",-3f..3f,.01f)!!,.0001f)
        assertEquals(.36f,parseParameter(".356",-3f..3f,.01f)!!,.0001f)
    }
}
