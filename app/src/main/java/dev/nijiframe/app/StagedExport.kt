// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class ExportPhase(val label: String, val cancellable: Boolean = true) {
    RENDERING("正在处理照片"), ENCODING("正在编码照片"), PACKAGING("正在打包工程"),
    WRITING("正在写入文件，请稍候", false)
}

/** Prepare completely before opening the destination. Providers do not promise atomic replacement. */
internal object StagedExport {
    fun prune(directory: File) {
        directory.listFiles()?.filter { it.name.startsWith("export-") && it.extension=="tmp" &&
            System.currentTimeMillis()-it.lastModified()>86_400_000 }?.forEach { it.delete() }
    }

    suspend fun write(directory: File, beforeWrite: suspend () -> Unit,
                      openDestination: () -> OutputStream, produce: suspend (OutputStream) -> Unit) {
        val context=currentCoroutineContext()
        context.ensureActive()
        if(!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建导出缓存，请检查存储空间")
        val temp=File.createTempFile("export-", ".tmp", directory)
        try {
            temp.outputStream().buffered(64*1024).use { output ->
                val checked=object : FilterOutputStream(output) {
                    override fun write(value: Int) {context.ensureActive();out.write(value)}
                    override fun write(bytes: ByteArray,offset: Int,length: Int) {
                        context.ensureActive();out.write(bytes,offset,length)
                    }
                    override fun flush() {context.ensureActive();out.flush()}
                    // The producer does not own the temporary file's stream.
                    override fun close() {flush()}
                }
                produce(checked)
                context.ensureActive()
            }
            context.ensureActive()
            beforeWrite()
            // A cancellation arriving while the UI changes phase must still leave the destination untouched.
            context.ensureActive()
            withContext(NonCancellable + Dispatchers.IO) {
                // Stop accepting cancellation before truncation. A provider failure can still leave a partial file.
                openDestination().use { output ->
                    temp.inputStream().use { it.copyTo(output,64*1024) }
                    output.flush()
                }
            }
        } finally {temp.delete()}
    }
}
