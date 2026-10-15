// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

/** Admission check, not a guarantee: other apps and codec allocations can change available memory. */
internal object ImageMemory {
    fun available(context: Context): Long {
        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        val appHeadroom = runtime.maxMemory() - heapUsed - Debug.getNativeHeapAllocatedSize()
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        val systemHeadroom = if (info.lowMemory) 0 else info.availMem - maxOf(info.threshold, 64L * 1024 * 1024)
        return minOf(appHeadroom, systemHeadroom / 2).coerceAtLeast(0)
    }

    fun requireExport(required: Long, available: Long) {
        require(required <= available) {
            "当前内存不足以导出此尺寸，请选择长边 4000、2048 或更小尺寸；原图和工程仍完整保留"
        }
    }
}
