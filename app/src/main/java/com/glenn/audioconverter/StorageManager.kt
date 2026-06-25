package com.glenn.audioconverter

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Writes output AAC files to public Downloads directory.
 *
 * Primary:   MediaStore.Downloads (SAF, Android 10+).
 * Fallback:  getExternalFilesDir(DOWNLOADS) (app-private).
 */
object StorageManager {

    private const val TAG = "StorageManager"

    sealed class WriteResult {
        data class Success(val path: String) : WriteResult()
        data class Failed(val reason: String, val fallbackPath: String?) : WriteResult()
    }

    /**
     * Save AAC file to Downloads. Returns the public path on success,
     * or fallback path with error message on failure.
     */
    fun saveAac(
        context: Context,
        sourceFile: File,
        displayName: String
    ): WriteResult {
        val name = if (displayName.endsWith(".aac")) displayName else "${displayName}.aac"

        // Primary: MediaStore
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/aac")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { it.copyTo(out) }
                }

                // Trigger media scan
                MediaScannerConnection.scanFile(
                    context, arrayOf(sourceFile.absolutePath), null, null
                )

                return WriteResult.Success("Downloads/$name")
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore write failed, trying fallback", e)
        }

        // Fallback: app-private external
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val dest = File(dir, name)
            sourceFile.copyTo(dest, overwrite = true)
            Log.i(TAG, "Fallback save: ${dest.absolutePath}")
            WriteResult.Failed(
                reason = "公共目录写入失败，已保存至应用私有目录",
                fallbackPath = dest.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Fallback save failed", e)
            WriteResult.Failed(
                reason = "写入失败: ${e.message}",
                fallbackPath = null
            )
        }
    }
}
