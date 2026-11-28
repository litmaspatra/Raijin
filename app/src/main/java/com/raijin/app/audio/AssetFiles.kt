package com.raijin.app.audio

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * Copies a bundled asset to a real file once. Uses only a plain stream: assets that the build
 * stores compressed (such as .txt files) cannot be opened as a file descriptor, so the old
 * size check through openFd failed for them. A small marker file records which app install the
 * copy came from, so a new APK refreshes the copy.
 */
object AssetFiles {
    fun ensureCopied(open: () -> InputStream, target: File, stamp: String) {
        val marker = File(target.path + ".stamp")
        if (target.exists() && target.length() > 0 && marker.exists() && marker.readText() == stamp) return
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".part")
        open().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        check(tmp.length() > 0) { "empty asset: ${target.name}" }
        target.delete()
        check(tmp.renameTo(target)) { "copy failed: ${target.name}" }
        marker.writeText(stamp)
    }

    fun ensureCopied(context: Context, assetPath: String, target: File) =
        ensureCopied({ context.assets.open(assetPath) }, target, installStamp(context))

    fun installStamp(context: Context): String = try {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        "${p.lastUpdateTime}"
    } catch (_: Exception) {
        "0"
    }
}
