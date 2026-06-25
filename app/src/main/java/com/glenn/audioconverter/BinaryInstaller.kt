package com.glenn.audioconverter

import android.content.Context
import java.io.File

/**
 * Extracts ffmpeg / ffprobe from res/raw to filesDir/bin.
 * Sets executable permission and verifies with canExecute().
 */
object BinaryInstaller {

    private const val BIN_DIR = "bin"
    private const val FFMPEG = "ffmpeg"
    private const val FFPROBE = "ffprobe"

    @Volatile
    private var installed = false

    /** Returns the absolute path to the directory containing ffmpeg/ffprobe. */
    fun binDir(context: Context): File {
        return File(context.filesDir, BIN_DIR)
    }

    fun ffmpegPath(context: Context): String {
        return File(binDir(context), FFMPEG).absolutePath
    }

    fun ffprobePath(context: Context): String {
        return File(binDir(context), FFPROBE).absolutePath
    }

    fun isInstalled(): Boolean = installed

    /**
     * Install binaries if not already present.
     * Throws RuntimeException if install or verification fails.
     */
    fun install(context: Context) {
        if (installed) return

        val dir = binDir(context)
        if (!dir.exists()) dir.mkdirs()

        installOne(context, FFMPEG, R.raw.ffmpeg)
        installOne(context, FFPROBE, R.raw.ffprobe)

        installed = true
    }

    private fun installOne(context: Context, name: String, resId: Int) {
        val dest = File(binDir(context), name)

        // Skip copy if already exists and executable
        if (dest.exists() && dest.canExecute()) return

        context.resources.openRawResource(resId).use { src ->
            dest.outputStream().use { dst -> src.copyTo(dst) }
        }

        // Set permissions (world-executable NOT set, only owner)
        val ok = dest.setExecutable(true, false) && dest.setReadable(true, false)
        if (!ok) {
            throw RuntimeException("无法设置执行权限: $name")
        }

        // Verify
        if (!dest.canExecute()) {
            throw RuntimeException("核心组件安装失败: $name 无法执行")
        }
    }

    /**
     * Re-verify and re-apply permissions. Call before each ProcessBuilder execution.
     * Returns true if binary is ready.
     */
    fun ensureExecutable(context: Context): Boolean {
        return try {
            val ffmpeg = File(binDir(context), FFMPEG)
            val ffprobe = File(binDir(context), FFPROBE)
            ffmpeg.canExecute() && ffprobe.canExecute()
        } catch (e: Exception) {
            false
        }
    }

    /** Retry permissions once */
    fun retryPermissions(context: Context): Boolean {
        return try {
            val ffmpeg = File(binDir(context), FFMPEG)
            val ffprobe = File(binDir(context), FFPROBE)
            ffmpeg.setExecutable(true, false)
            ffprobe.setExecutable(true, false)
            ffmpeg.canExecute() && ffprobe.canExecute()
        } catch (e: Exception) {
            false
        }
    }
}
