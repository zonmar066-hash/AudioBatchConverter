package com.glenn.audioconverter

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * ProcessBuilder-based FFmpeg/FFprobe executor.
 *
 * - Serial job queue (Channel + single worker coroutine)
 * - Per-job timeout (default 5 minutes)
 * - Pre-execution binary permission check + retry
 * - Captures stdout/stderr
 */
object FFmpegRunner {

    private const val TAG = "FFmpegRunner"
    private const val DEFAULT_TIMEOUT_SEC = 300L
    private const val DEFAULT_TIMEOUT_MS = DEFAULT_TIMEOUT_SEC * 1000

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = Channel<Job>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in queue) {
                try {
                    job.deferred.complete(runBlocking { executeInternal(job.cmd, job.timeoutMs) })
                } catch (e: Exception) {
                    job.deferred.completeExceptionally(e)
                }
            }
        }
    }

    private class Job(
        val cmd: List<String>,
        val timeoutMs: Long,
        val deferred: CompletableDeferred<ExecResult>
    )

    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    /**
     * Enqueue command and wait for result.
     */
    suspend fun execute(cmd: List<String>, timeoutSec: Long = DEFAULT_TIMEOUT_SEC): ExecResult {
        val deferred = CompletableDeferred<ExecResult>()
        queue.send(Job(cmd, timeoutSec * 1000, deferred))
        return deferred.await()
    }

    /**
     * Fire-and-forget (for simple ffprobe calls from non-suspend context).
     */
    fun executeBlocking(cmd: List<String>, timeoutSec: Long = DEFAULT_TIMEOUT_SEC): ExecResult {
        return runBlocking { execute(cmd, timeoutSec) }
    }

    private suspend fun executeInternal(
        cmd: List<String>,
        timeoutMs: Long
    ): ExecResult = withContext(Dispatchers.IO) {
        // Pre-flight: verify binaries
        val binaryPath = cmd.firstOrNull() ?: return@withContext ExecResult(-1, "", "Empty command")
        if (!verifyBinary(binaryPath)) {
            return@withContext ExecResult(-1, "", "Binary verification failed: $binaryPath")
        }

        Log.d(TAG, "Exec: ${cmd.joinToString(" ")}")

        val process = try {
            ProcessBuilder(cmd)
                .directory(App.instance.cacheDir)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            return@withContext ExecResult(-1, "", "Process start failed: ${e.message}")
        }

        val stdoutBuf = ByteArrayOutputStream()
        val stderrBuf = ByteArrayOutputStream()

        val stdoutJob = launch(Dispatchers.IO) {
            process.inputStream.copyTo(stdoutBuf)
        }
        val stderrJob = launch(Dispatchers.IO) {
            process.errorStream.copyTo(stderrBuf)
        }

        val finished = try {
            withTimeout(timeoutMs) {
                process.waitFor()
                true
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "Process timed out after ${timeoutMs}ms, destroying")
            process.destroyForcibly()
            stdoutJob.cancel()
            stderrJob.cancel()
            false
        }

        if (!finished) {
            try { process.waitFor(5, TimeUnit.SECONDS) } catch (_: Exception) {}
        }

        stdoutJob.join()
        stderrJob.join()

        val exitCode = if (finished) process.exitValue() else -1
        val stdout = stdoutBuf.toString("UTF-8")
        val stderr = stderrBuf.toString("UTF-8")

        if (exitCode != 0) {
            Log.w(TAG, "Non-zero exit($exitCode): ${cmd.firstOrNull()}")
            Log.d(TAG, "Stderr: ${stderr.take(500)}")
        }

        ExecResult(exitCode, stdout, stderr)
    }

    private fun verifyBinary(path: String): Boolean {
        val file = java.io.File(path)
        if (!file.exists() || !file.canExecute()) {
            Log.w(TAG, "Binary not executable: $path, retrying permissions")
            if (!BinaryInstaller.retryPermissions(App.instance)) {
                Log.e(TAG, "Permission retry failed for $path")
                return false
            }
            if (!file.canExecute()) {
                Log.e(TAG, "Still not executable after retry: $path")
                return false
            }
        }
        return true
    }
}
