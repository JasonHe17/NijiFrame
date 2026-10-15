// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class AvifOrientationTest {
    private fun data(block: DataOutputStream.()->Unit)=ByteArrayOutputStream().also {DataOutputStream(it).apply(block)}.toByteArray()
    private fun box(type: String,body: ByteArray,extended: Boolean=false)=data {
        writeInt(if(extended) 1 else body.size+8);writeBytes(type)
        if(extended) writeLong(body.size+16L)
        write(body)
    }
    private fun container(primary: Int=1,index: Int=2,extended: Boolean=false,wide: Boolean=false): ByteArray {
        val pitm=box("pitm",data {writeInt(if(wide) 0x01000000 else 0);if(wide) writeInt(primary) else writeShort(primary)})
        val properties=box("ipco",box("irot",byteArrayOf(3))+box("imir",byteArrayOf(1)))
        val associations=box("ipma",data {
            writeInt(if(wide) 0x01000001 else 0);writeInt(2)
            // Auxiliary item 2 rotates; primary item 1 mirrors. Their transforms must not be combined.
            for((id,property) in listOf(2 to 1,1 to index)) {
                if(wide) writeInt(id) else writeShort(id)
                writeByte(1)
                if(wide) writeShort(0x8000 or property) else writeByte(0x80 or property)
            }
        })
        return box("meta",data {writeInt(0);write(pitm);write(box("iprp",properties+associations))},extended)
    }
    @Test fun primaryAssociationsExcludeAuxiliaryTransforms() {
        assertEquals(2,AvifOrientation.read(container()))
        assertEquals(6,AvifOrientation.read(container(primary=2)))
        assertEquals(1,AvifOrientation.read(container(index=0)))
    }
    @Test fun extendedBoxesAndWideItemAssociationsRetainMeaning() {
        assertEquals(2,AvifOrientation.read(container(extended=true,wide=true)))
        assertEquals(6,AvifOrientation.read(container(primary=2,extended=true,wide=true)))
    }
    @Test fun truncatedBoxesAndInvalidPropertiesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {AvifOrientation.read(container(index=3))}
        assertThrows(IllegalArgumentException::class.java) {AvifOrientation.read(container().dropLast(1).toByteArray())}
        assertThrows(IllegalArgumentException::class.java) {AvifOrientation.read(byteArrayOf(0,0,0,4,109,101,116,97))}
    }
}
