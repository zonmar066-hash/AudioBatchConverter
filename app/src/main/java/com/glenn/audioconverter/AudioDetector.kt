package com.glenn.audioconverter

import android.util.Log

/**
 * Detects audio tracks in media files using ffprobe.
 *
 * Probe order:
 *   1. stream=index,sample_rate,codec_name → CSV parse, first valid audio stream
 *   2. show_streams → grep for codec_type=audio, extract sample_rate
 *   3. Fallback: count audio streams; if >0 but no sample_rate → default 44100
 */
object AudioDetector {

    private const val TAG = "AudioDetector"
    const val DEFAULT_SAMPLE_RATE = 44100

    data class AudioInfo(
        val hasAudio: Boolean,
        val sampleRate: Int?,
        val codecName: String?,
        val logNote: String? = null
    )

    suspend fun detect(ffprobePath: String, inputFile: String): AudioInfo {
        // Pass 1: structured CSV extract
        val csvResult = FFmpegRunner.execute(
            listOf(ffprobePath, "-v", "error",
                "-select_streams", "a",
                "-show_entries", "stream=index,sample_rate,codec_name",
                "-of", "csv=p=0", inputFile),
            timeoutSec = 10
        )

        val csv = csvResult.stdout
        if (csv.isNotBlank()) {
            val lines = csv.trim().lines()
            for (line in lines) {
                val parts = line.split(",")
                val srStr = parts.getOrNull(1)?.trim()
                val codec = parts.getOrNull(2)?.trim()
                val sr = srStr?.toIntOrNull()
                if (sr != null && sr > 0) {
                    Log.i(TAG, "Detected audio: ${sr}Hz, $codec")
                    return AudioInfo(hasAudio = true, sampleRate = sr, codecName = codec)
                }
            }
            Log.w(TAG, "CSV output had no valid sample_rate, trying fallback")
        }

        // Pass 2: full stream dump
        val fullResult = FFmpegRunner.execute(
            listOf(ffprobePath, "-v", "error", "-show_streams", inputFile),
            timeoutSec = 10
        )
        val full = fullResult.stdout

        if (full.isNotBlank()) {
            val hasAudioStream = full.lineSequence().any { it.trim() == "codec_type=audio" }
            if (hasAudioStream) {
                val sr = full.lineSequence()
                    .dropWhile { it.trim() != "codec_type=audio" }
                    .firstOrNull { it.trim().startsWith("sample_rate=") }
                    ?.trim()
                    ?.removePrefix("sample_rate=")
                    ?.toIntOrNull()
                    ?: DEFAULT_SAMPLE_RATE

                return AudioInfo(
                    hasAudio = true,
                    sampleRate = sr,
                    codecName = null,
                    logNote = if (sr == DEFAULT_SAMPLE_RATE) "未检测到采样率，已按${DEFAULT_SAMPLE_RATE}Hz处理" else null
                )
            }
        }

        // Pass 3: count audio streams
        val countResult = FFmpegRunner.execute(
            listOf(ffprobePath, "-v", "error",
                "-select_streams", "a",
                "-show_entries", "stream=index",
                "-of", "csv=p=0", inputFile),
            timeoutSec = 10
        )
        val streamCount = countResult.stdout.trim().lines().count { it.isNotBlank() }

        return if (streamCount > 0) {
            AudioInfo(
                hasAudio = true,
                sampleRate = DEFAULT_SAMPLE_RATE,
                codecName = null,
                logNote = "未检测到采样率，已按${DEFAULT_SAMPLE_RATE}Hz处理"
            )
        } else {
            AudioInfo(hasAudio = false, sampleRate = null, codecName = null)
        }
    }
}
