// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

/** Read primary-item irot/imir associations, never auxiliary-image transforms.
 * Transform definitions: https://github.com/AOMediaCodec/libavif/blob/main/include/avif/avif.h
 * Returns EXIF-equivalent orientation for an untransformed platform bitmap.
 */
object AvifOrientation {
    private data class Box(val type: String,val data: Int,val end: Int)
    fun read(bytes: ByteArray): Int {
        fun number(at: Int,size: Int,end: Int=bytes.size): Long {
            require(at>=0 && at.toLong()+size<=end) {"Invalid AVIF metadata bounds"}
            return (0 until size).fold(0L) {n,i->(n shl 8) or (bytes[at+i].toLong() and 255)}
        }
        fun boxes(start: Int,end: Int): List<Box> {
            val result=mutableListOf<Box>();var at=start
            while(at<end) {
                var length=number(at,4,end);var header=8
                require(at.toLong()+8<=end) {"Truncated AVIF box"}
                if(length==1L) {
                    require(number(at+8,4,end)==0L) {"AVIF box exceeds resource limit"}
                    length=number(at+12,4,end);header=16
                } else if(length==0L) length=(end-at).toLong()
                require(length>=header && at.toLong()+length<=end) {"Invalid AVIF box size"}
                result+=Box(String(bytes,at+4,4,Charsets.US_ASCII),at+header,(at+length).toInt())
                at+=length.toInt()
            }
            return result
        }
        val meta=boxes(0,bytes.size).singleOrNull {it.type=="meta"} ?: return 1
        require(number(meta.data,1,meta.end)==0L) {"Unsupported AVIF meta version"}
        val children=boxes(meta.data+4,meta.end)
        val pitm=children.singleOrNull {it.type=="pitm"} ?: return 1
        val version=number(pitm.data,1,pitm.end)
        require(version in 0..1) {"Unsupported AVIF primary item version"}
        val primary=number(pitm.data+4,if(version==0L) 2 else 4,pitm.end)
        val iprp=children.singleOrNull {it.type=="iprp"} ?: return 1
        val propertyBoxes=boxes(iprp.data,iprp.end)
        val ipco=propertyBoxes.singleOrNull {it.type=="ipco"} ?: return 1
        val properties=boxes(ipco.data,ipco.end)
        val selected=mutableListOf<Box>()
        for(ipma in propertyBoxes.filter {it.type=="ipma"}) {
            val v=number(ipma.data,1,ipma.end)
            require(v in 0..1) {"Unsupported AVIF association version"}
            val flags=number(ipma.data+1,3,ipma.end)
            val count=number(ipma.data+4,4,ipma.end)
            require(count<=(ipma.end-ipma.data)/3) {"Invalid AVIF association count"}
            var at=ipma.data+8
            repeat(count.toInt()) {
                val idSize=if(v==0L) 2 else 4
                val id=number(at,idSize,ipma.end);at+=idSize
                val associations=number(at++,1,ipma.end).toInt()
                repeat(associations) {
                    val size=if(flags and 1L==0L) 1 else 2
                    val index=(number(at,size,ipma.end) and if(size==1) 127L else 32767L).toInt();at+=size
                    require(index<=properties.size) {"Invalid AVIF property index"}
                    if(id==primary && index>0) selected+=properties[index-1]
                }
            }
        }
        // Matrices act in property association order; translation is handled by Bitmap.
        var matrix=intArrayOf(1,0,0,1)
        fun apply(a: Int,b: Int,c: Int,d: Int) {
            val m=matrix;matrix=intArrayOf(a*m[0]+b*m[2],a*m[1]+b*m[3],c*m[0]+d*m[2],c*m[1]+d*m[3])
        }
        for(p in selected) when(p.type) {
            "irot" -> {
                val angle=number(p.data,1,p.end).toInt()
                require(angle in 0..3) {"Invalid AVIF rotation"}
                repeat(angle) {apply(0,1,-1,0)} // counter-clockwise, image coordinates
            }
            "imir" -> when(number(p.data,1,p.end).toInt()) {
                0 -> apply(1,0,0,-1) // exchange top/bottom
                1 -> apply(-1,0,0,1) // exchange left/right
                else -> error("Invalid AVIF mirror axis")
            }
        }
        val orientations=listOf(intArrayOf(1,0,0,1),intArrayOf(-1,0,0,1),intArrayOf(-1,0,0,-1),
            intArrayOf(1,0,0,-1),intArrayOf(0,1,1,0),intArrayOf(0,-1,1,0),intArrayOf(0,-1,-1,0),intArrayOf(0,1,-1,0))
        return orientations.indexOfFirst {it.contentEquals(matrix)}.also {check(it>=0)}+1
    }
}
