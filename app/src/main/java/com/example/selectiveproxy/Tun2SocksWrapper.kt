package com.example.selectiveproxy

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class Tun2SocksWrapper(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    private val socksPort: Int
) {
    companion object {
        private const val TAG = "Tun2SocksWrapper"
    }

    private var process: Process? = null
    private val isRunning = AtomicBoolean(false)
    private var watchdogThread: Thread? = null

    fun start() {
        if (isRunning.get()) return

        try {
            // Android 10+ (API 29+) forbids executing binaries from the application's home directory (W^X violation).
            // To work around this, we package the binary as a native library (libtun2socks.so) in jniLibs.
            // Android extracts it to the system-managed nativeLibraryDir, where execution is allowed.
            val libPath = File(context.applicationInfo.nativeLibraryDir, "libtun2socks.so")

            if (!libPath.exists()) {
                 Log.e(TAG, "tun2socks binary not found at ${libPath.absolutePath}")
                 throw RuntimeException("libtun2socks.so missing")
            }

            // Using xjasonlyu/tun2socks binary syntax.
            // It expects: -device fd://<fd> -proxy socks5://<host>:<port>
            val tunFd = vpnInterface.fd

            val cmd = arrayOf(
                libPath.absolutePath,
                "-device", "fd://$tunFd",
                "-proxy", "socks5://127.0.0.1:$socksPort",
                "-loglevel", "warning"
            )

            val builder = ProcessBuilder(*cmd)
                .directory(File(context.applicationInfo.nativeLibraryDir))
                .redirectErrorStream(true)

            process = builder.start()
            isRunning.set(true)

            watchdogThread = Thread {
                while (isRunning.get()) {
                    try {
                        Thread.sleep(5000)
                        if (process?.isAlive != true) {
                            Log.e(TAG, "tun2socks died, restarting...")
                            restart()
                        }
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }.apply { start() }

            Thread {
                val reader = process?.inputStream?.bufferedReader()
                reader?.forEachLine { line ->
                    when {
                        line.contains("ERROR", ignoreCase = true) -> Log.e(TAG, "tun2socks: $line")
                        line.contains("WARNING", ignoreCase = true) -> Log.w(TAG, "tun2socks: $line")
                        line.contains("INFO", ignoreCase = true) -> Log.i(TAG, "tun2socks: $line")
                    }
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tun2socks", e)
            isRunning.set(false)
            throw RuntimeException("tun2socks start failed", e)
        }
    }

    private fun restart() {
        stop()
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            // ignore
        }
        start()
    }

    fun stop() {
        isRunning.set(false)
        watchdogThread?.interrupt()
        watchdogThread = null
        process?.destroy()
        process = null
    }
}
