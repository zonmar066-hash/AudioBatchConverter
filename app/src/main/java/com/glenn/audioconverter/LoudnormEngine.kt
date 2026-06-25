package com.glenn.audioconverter

import android.util.Log
import org.json.JSONObject

/**
 * Two-pass loudness normalization via FFmpeg loudnorm filter.
 *
 * Pass 1:  Measure integrated loudness (JSON on stderr).
 * Pass 2:  Apply measured values, encode to AAC.
 *
 * Fallbacks (in order):
 *   - JSON parse failure          → single-pass loudnorm (linear=false)
 *   - input_i < -70 (silence)     → skip normalization, direct transcode
 *   - Non-zero exit               → dynaudnorm fallback
 */
object LoudnormEngine {

    private const val TAG = "LoudnormEngine"
    private const val TARGET_I = -14.0
    private const val TARGET_LRA = 11.0
    private const val TARGET_TP = -1.0
    private const val AAC_BITRATE = "192k"

    data class LmResult(
        val success: Boolean,
        val logNote: String,
        val outputFile: String? = null
    )

    /**
     * Execute loudness-normalized AAC encoding.
     *
     * @param ffmpegPath   absolute path to ffmpeg binary
     * @param ffprobePath  absolute path to ffprobe (unused but kept for symmetry)
     * @param inputFile    source media file
     * @param outputFile   destination .aac file
     * @param sampleRate   target sample rate in Hz
     */
    suspend fun run(
        ffmpegPath: String,
        ffprobePath: String,
        inputFile: String,
        outputFile: String,
        sampleRate: Int
    ): LmResult = try {
        // ── Pass 1: measure ──
        val measureCmd = listOf(
            ffmpegPath,
            "-i", inputFile,
            "-af", "loudnorm=I=$TARGET_I:LRA=$TARGET_LRA:TP=$TARGET_TP:print_format=json",
            "-f", "null", "-"
        )

        val measureOutput = FFmpegRunner.execute(measureCmd, timeoutSec = 60)
        val jsonStr = extractJson(measureOutput)

        if (jsonStr != null) {
            val json = try { JSONObject(jsonStr) } catch (_: Exception) { null }

            if (json != null) {
                val inputI = json.optDouble("input_i", Double.NaN)

                if (!inputI.isNaN() && inputI > -70.0) {
                    // ── Pass 2: apply measured values ──
                    val inputTp = json.optDouble("input_tp", 0.0)
                    val inputLra = json.optDouble("input_lra", 0.0)
                    val inputThresh = json.optDouble("input_thresh", 0.0)

                    val encodeCmd = listOf(
                        ffmpegPath,
                        "-i", inputFile,
                        "-af", "loudnorm=I=$TARGET_I:LRA=$TARGET_LRA:TP=$TARGET_TP:" +
                                "measured_I=$inputI:measured_LRA=$inputLra:" +
                                "measured_TP=$inputTp:measured_thresh=$inputThresh:" +
                                "linear=true:print_format=summary",
                        "-c:a", "aac",
                        "-b:a", AAC_BITRATE,
                        "-ar", sampleRate.toString(),
                        "-y",
                        outputFile
                    )

                    val result = FFmpegRunner.execute(encodeCmd, timeoutSec = 300)
                    if (result.exitCode != 0) {
                        Log.w(TAG, "Loudnorm encode failed (exit=${result.exitCode}), falling back to dynaudnorm")
                        return dynaudnormFallback(ffmpegPath, inputFile, outputFile, sampleRate)
                    }

                    return LmResult(
                        success = true,
                        logNote = "响度归一化 -14 LUFS (原始 ${"%.1f".format(inputI)} LUFS)",
                        outputFile = outputFile
                    )
                } else {
                    // input_i too low — silence, skip normalization
                    Log.i(TAG, "input_i=${inputI} below -70, skipping normalization")
                    return directTranscode(ffmpegPath, inputFile, outputFile, sampleRate,
                        "静音信号，已跳过标准化")
                }
            }
        }

        // JSON parse failed / missing
        Log.w(TAG, "Loudnorm JSON not available, using single-pass")
        return singlePassLoudnorm(ffmpegPath, inputFile, outputFile, sampleRate)

    } catch (e: Exception) {
        Log.e(TAG, "Loudnorm engine exception", e)
        try {
            return dynaudnormFallback(ffmpegPath, inputFile, outputFile, sampleRate)
        } catch (e2: Exception) {
            return LmResult(success = false, logNote = "标准化失败: ${e2.message}")
        }
    }

    // ── Fallback paths ────────────────────────────────────────────────

    private suspend fun singlePassLoudnorm(
        ffmpegPath: String, inputFile: String, outputFile: String, sr: Int
    ): LmResult {
        val cmd = listOf(
            ffmpegPath,
            "-i", inputFile,
            "-af", "loudnorm=I=$TARGET_I:LRA=$TARGET_LRA:TP=$TARGET_TP:linear=false",
            "-c:a", "aac",
            "-b:a", AAC_BITRATE,
            "-ar", sr.toString(),
            "-y",
            outputFile
        )
        val result = FFmpegRunner.execute(cmd, timeoutSec = 300)
        return if (result.exitCode == 0) {
            LmResult(success = true,
                logNote = "单步响度归一化 -14 LUFS (JSON解析失败，已回退)",
                outputFile = outputFile)
        } else {
            dynaudnormFallback(ffmpegPath, inputFile, outputFile, sr)
        }
    }

    private suspend fun dynaudnormFallback(
        ffmpegPath: String, inputFile: String, outputFile: String, sr: Int
    ): LmResult {
        val cmd = listOf(
            ffmpegPath,
            "-i", inputFile,
            "-af", "dynaudnorm",
            "-c:a", "aac",
            "-b:a", AAC_BITRATE,
            "-ar", sr.toString(),
            "-y",
            outputFile
        )
        val result = FFmpegRunner.execute(cmd, timeoutSec = 300)
        return if (result.exitCode == 0) {
            LmResult(success = true,
                logNote = "已回退至动态标准化 (dynaudnorm)",
                outputFile = outputFile)
        } else {
            LmResult(success = false,
                logNote = "标准化失败: FFmpeg exit code=${result.exitCode}")
        }
    }

    private suspend fun directTranscode(
        ffmpegPath: String, inputFile: String, outputFile: String, sr: Int, note: String
    ): LmResult {
        val cmd = listOf(
            ffmpegPath,
            "-i", inputFile,
            "-c:a", "aac",
            "-b:a", AAC_BITRATE,
            "-ar", sr.toString(),
            "-y",
            outputFile
        )
        val result = FFmpegRunner.execute(cmd, timeoutSec = 300)
        return if (result.exitCode == 0) {
            LmResult(success = true, logNote = note, outputFile = outputFile)
        } else {
            LmResult(success = false, logNote = "转码失败: exit=${result.exitCode}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** Extract JSON object from FFmpeg stderr output. */
    private fun extractJson(output: String): String? {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return output.substring(start, end + 1)
    }
}
