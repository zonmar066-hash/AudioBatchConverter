package com.glenn.audioconverter

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {

    companion object {
        lateinit var instance: App
            private set

        /** buffer of uncaught exception messages surfaced to UI */
        val crashLog = mutableListOf<String>()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Set global exception handler before anything else
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val msg = "未捕获异常 [${thread.name}]: ${throwable.message}"
            Log.e("AudioConverter", msg, throwable)
            crashLog.add("$msg\n${sw}")
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Install FFmpeg binaries on background thread
        Thread {
            try {
                BinaryInstaller.install(this)
            } catch (e: Exception) {
                Log.e("AudioConverter", "Binary install failed", e)
                crashLog.add("核心组件安装失败: ${e.message}")
            }
        }.start()
    }
}
