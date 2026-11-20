package com.raijin.app.storage

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Tactical Storage & Cache Auditor.
 *
 * Guarantees zero unapproved file deletion:
 * - Scans application cache, external temporary buffers, and staging logs.
 * - Does NOT touch user files, photos, downloads, or external documents.
 * - Requires explicit itemized approval before pruning.
 */
data class StorageItem(
    val id: String,
    val title: String,
    val description: String,
    val sizeBytes: Long,
    val isSafeJunk: Boolean = true,
)

data class StorageAuditReport(
    val items: List<StorageItem>,
    val totalSizeBytes: Long,
    val timestamp: Long = System.currentTimeMillis(),
)

class StorageAuditor(private val context: Context) {

    companion object {
        private const val TAG = "StorageAuditor"

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 KB"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                else -> String.format("%.0f KB", kb)
            }
        }
    }

    /**
     * Performs a read-only audit of temporary files and cache.
     * No files are deleted during scan.
     */
    fun scan(): StorageAuditReport {
        val items = mutableListOf<StorageItem>()

        // 1. App internal cache (temporary HTTP cache, UI renders, shader blobs)
        val internalCache = context.cacheDir
        val internalSize = getFolderSize(internalCache)
        items.add(
            StorageItem(
                id = "internal_cache",
                title = "App Internal Cache & Shaders",
                description = "Temporary cache buffers, HTTP response cache, and UI render cache",
                sizeBytes = internalSize,
            )
        )

        // 2. External app cache (temporary download chunks, staged media caches)
        val externalCache = context.externalCacheDir
        val externalSize = if (externalCache != null) getFolderSize(externalCache) else 0L
        items.add(
            StorageItem(
                id = "external_cache",
                title = "External App Cache",
                description = "Staged media files and temporary download fragments",
                sizeBytes = externalSize,
            )
        )

        // 3. Staged telemetry and diagnostic crash dumps
        val logsDir = File(context.filesDir, "logs")
        val logsSize = if (logsDir.exists()) getFolderSize(logsDir) else 0L
        items.add(
            StorageItem(
                id = "logs_cache",
                title = "Telemetry & Staging Logs",
                description = "Historical telemetry dumps and temporary diagnostic traces",
                sizeBytes = logsSize,
            )
        )

        val total = items.sumOf { it.sizeBytes }
        Log.i(TAG, "Storage audit scan complete: ${formatBytes(total)} junk identified across ${items.size} categories")

        return StorageAuditReport(
            items = items,
            totalSizeBytes = total,
        )
    }

    /**
     * Prunes ONLY the categories explicitly approved by the user.
     * Returns the exact number of bytes freed.
     */
    fun cleanApproved(approvedIds: Set<String>): Long {
        var bytesFreed = 0L

        if (approvedIds.contains("internal_cache")) {
            val freed = deleteFolderContents(context.cacheDir)
            bytesFreed += freed
            Log.i(TAG, "Pruned internal cache: ${formatBytes(freed)}")
        }

        if (approvedIds.contains("external_cache")) {
            val freed = deleteFolderContents(context.externalCacheDir)
            bytesFreed += freed
            Log.i(TAG, "Pruned external cache: ${formatBytes(freed)}")
        }

        if (approvedIds.contains("logs_cache")) {
            val logsDir = File(context.filesDir, "logs")
            if (logsDir.exists()) {
                val freed = deleteFolderContents(logsDir)
                bytesFreed += freed
                Log.i(TAG, "Pruned telemetry logs: ${formatBytes(freed)}")
            }
        }

        Log.i(TAG, "Storage cleaning completed: ${formatBytes(bytesFreed)} total freed")
        return bytesFreed
    }

    private fun getFolderSize(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length()
        var size = 0L
        file.listFiles()?.forEach { child ->
            size += getFolderSize(child)
        }
        return size
    }

    private fun deleteFolderContents(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        var freed = 0L
        file.listFiles()?.forEach { child ->
            freed += getFolderSize(child)
            child.deleteRecursively()
        }
        return freed
    }
}
