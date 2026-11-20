package com.raijin.app.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MU-1: scans the owner's on-device audio via MediaStore. Requires
 * READ_MEDIA_AUDIO (API 33+) or READ_EXTERNAL_STORAGE (below); when the
 * permission is missing it returns an empty library and logs, so music
 * simply falls back to Tier 2 instead of crashing or pretending.
 */
class MediaStoreMusicScanner(private val context: Context) : MusicLibraryScanner {

    override suspend fun scan(): List<LibraryTrack> = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            Log.i(TAG, "Media audio permission not granted; local library empty (Tier 2 fallback)")
            return@withContext emptyList()
        }
        val tracks = mutableListOf<LibraryTrack>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id,
                    )
                    tracks += LibraryTrack(
                        id = id,
                        title = cursor.getString(titleCol) ?: continue,
                        artist = cursor.getString(artistCol)?.takeIf { it != "<unknown>" } ?: "",
                        album = cursor.getString(albumCol) ?: "",
                        durationMs = cursor.getLong(durationCol),
                        contentUri = uri.toString(),
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore scan failed; Tier 2 fallback: ${e.message}")
            return@withContext emptyList()
        }
        Log.i(TAG, "Local library scan: ${tracks.size} tracks")
        tracks
    }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "MediaStoreMusicScanner"
    }
}
