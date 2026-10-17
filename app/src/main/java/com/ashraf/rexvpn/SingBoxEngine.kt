
package com.ashraf.rexvpn

import android.content.Context
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus

class SingBoxEngine(
    private val context: Context,
    private val platformInterface: PlatformInterface
) : CommandServerHandler {

    companion object {
        private const val TAG = "REXVPN-SINGBOX"
        private var libboxInitialized = false

        @Synchronized
        private fun initializeLibbox(context: Context) {
            if (libboxInitialized) return

            val basePath = context.filesDir.absolutePath
            val tempPath = context.cacheDir.absolutePath

            val options = SetupOptions().apply {
                setBasePath(basePath)
                setWorkingPath(basePath)
                setTempPath(tempPath)
                setFixAndroidStack(true)
            }

            Libbox.setup(options)
            libboxInitialized = true

            Log.i(TAG, "Libbox initialized: $basePath")
        }
    }

    private var commandServer: CommandServer? = null
    private var running = false

    @Synchronized
    fun connect(configJson: String) {
        require(configJson.isNotBlank()) {
            "ملف إعدادات sing-box فارغ"
        }

        initializeLibbox(context)
        disconnect()

        val server = CommandServer(this, platformInterface)

        try {
            server.start()
            server.startOrReloadService(
                configJson,
                OverrideOptions()
            )

            commandServer = server
            running = true
            Log.i(TAG, "sing-box service started")

        } catch (error: Exception) {
            runCatching { server.closeService() }
            runCatching { server.close() }

            commandServer = null
            running = false

            Log.e(TAG, "Failed to start sing-box", error)
            throw error
        }
    }

    @Synchronized
    fun disconnect() {
        val server = commandServer

        commandServer = null
        running = false

        if (server != null) {
            runCatching {
                server.closeService()
            }.onFailure {
                Log.e(TAG, "Failed to close sing-box service", it)
            }

            runCatching {
                server.close()
            }.onFailure {
                Log.e(TAG, "Failed to close CommandServer", it)
            }
        }
    }

    @Synchronized
    fun isRunning(): Boolean = running

    override fun serviceStop() {
        synchronized(this) {
            running = false
        }
        Log.i(TAG, "sing-box requested service stop")
    }

    override fun serviceReload() {
        Log.i(TAG, "sing-box requested service reload")
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? {
        return SystemProxyStatus().apply {
            available = false
            enabled = false
        }
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        Log.i(TAG, "System proxy request: $isEnabled")
    }

    override fun triggerNativeCrash() {
        Log.w(TAG, "Native crash trigger ignored")
    }

    override fun writeDebugMessage(message: String?) {
        Log.d(TAG, message ?: "")
    }

    override fun connectSSHAgent(): Int {
        return -1
    }
}
