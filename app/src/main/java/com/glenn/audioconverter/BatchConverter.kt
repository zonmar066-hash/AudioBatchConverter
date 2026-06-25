package com.glenn.audioconverter

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orchestrates batch conversion:
 *   1. Copy selected files to cache
 *   2. For each: detect audio → adapt sample rate → loudnorm encode → save
 *   3. Report results via callback
 */
object BatchConverter {

    private const val TAG = "BatchConverter"

    data class ConversionResult(
        val originalName: String,
        val success: Boolean,
        val message: String
    )

    /**
     * @param files          list of (Uri, displayName) pairs
     * @param sampleRateChoice  "原始", "44100", "48000", etc.
     * @param onProgress     callback per-file with result
     */
    suspend fun convert(
        context: Context,
        files: List<Pair<Uri, String>>,
        sampleRateChoice: String,
        onProgress: suspend (Int, Int, ConversionResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val total = files.size
        val ffmpeg = BinaryInstaller.ffmpegPath(context)
        val ffprobe = BinaryInstaller.ffprobePath(context)

        // Verify binaries are ready
        if (!BinaryInstaller.ensureExecutable(context)) {
            for (i in files.indices) {
                onProgress(i + 1, total,
                    ConversionResult(files[i].second, false, "核心组件未就绪"))
            }
            return@withContext
        }

        val cacheDir = context.cacheDir

        for ((index, entry) in files.withIndex()) {
            val (uri, displayName) = entry
            val baseName = displayName
                .removeSuffix(".mp4").removeSuffix(".MP4")
                .removeSuffix(".mp3").removeSuffix(".MP3")
                .removeSuffix(".MPEG")
                .removeSuffix(".mov").removeSuffix(".MOV")
                .removeSuffix(".mkv").removeSuffix(".MKV")

            var cacheFile: File? = null
            try {
                // Step 1: Copy to cache
                cacheFile = File(cacheDir, "input_temp_$index")
                val copied = context.contentResolver.openInputStream(uri)?.use { src ->
                    cacheFile.outputStream().buffered().use { dst -> src.copyTo(dst) }
                    true
                } ?: false
                if (!copied) {
                    onProgress(index + 1, total,
                        ConversionResult(displayName, false, "无法读取文件"))
                    continue
                }

                val inputPath = cacheFile.absolutePath

                // Step 2: Detect audio
                val audioInfo = AudioDetector.detect(ffprobe, inputPath)
                if (!audioInfo.hasAudio) {
                    onProgress(index + 1, total,
                        ConversionResult(displayName, false, "无音频轨道"))
                    continue
                }

                val detectedSr = audioInfo.sampleRate ?: AudioDetector.DEFAULT_SAMPLE_RATE

                // Step 3: Adapt sample rate
                val adapt = SampleRateAdapter.adapt(detectedSr, sampleRateChoice)
                val logParts = mutableListOf<String>()
                adapt.logNote?.let { logParts.add(it) }
                audioInfo.logNote?.let { logParts.add(it) }

                // Step 4: Loudnorm encode
                val aacFile = File(cacheDir, "output_${index}.aac")
                val lmResult = LoudnormEngine.run(
                    ffmpeg, ffprobe, inputPath, aacFile.absolutePath, adapt.targetRate
                )

                logParts.add(lmResult.logNote)

                if (!lmResult.success) {
                    onProgress(index + 1, total,
                        ConversionResult(displayName, false, logParts.joinToString(" | ")))
                    continue
                }

                // Step 5: Save to Downloads
                val outputName = "${baseName}_audio.aac"
                val saveResult = StorageManager.saveAac(context, aacFile, outputName)

                val finalLog = when (saveResult) {
                    is StorageManager.WriteResult.Success -> {
                        logParts.add("已保存: ${saveResult.path}")
                        logParts.joinToString(" | ")
                    }
                    is StorageManager.WriteResult.Failed -> {
                        logParts.add(saveResult.reason)
                        if (saveResult.fallbackPath != null) {
                            logParts.add("备用路径: ${saveResult.fallbackPath}")
                        }
                        logParts.joinToString(" | ")
                    }
                }

                // Cleanup temp files
                aacFile.delete()

                val combinedSuccess = saveResult is StorageManager.WriteResult.Success
                onProgress(index + 1, total,
                    ConversionResult(displayName, combinedSuccess, finalLog))

            } catch (e: Exception) {
                Log.e(TAG, "Conversion failed for $displayName", e)
                onProgress(index + 1, total,
                    ConversionResult(displayName, false, "转换异常: ${e.message}"))
            } finally {
                cacheFile?.delete()
            }
        }
    }
}
