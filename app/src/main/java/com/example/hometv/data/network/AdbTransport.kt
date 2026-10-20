package com.example.hometv.data.network

import android.content.Context
import android.util.Log
import com.example.hometv.domain.model.RemoteCommand
import com.example.hometv.domain.model.TvDevice
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Optional ADB transport for TVs with Developer options -> Network debugging enabled.
 * The TV may ask the user to authorize this app's RSA key on first connection.
 */
class AdbTransport(
    context: Context,
    private val onLog: (String) -> Unit
) {

    private companion object {
        private const val TAG = "HomeTvLog"
        private const val PRIVATE_KEY = "adbkey"
        private const val PUBLIC_KEY = "adbkey.pub"
    }

    private val keyDirectory = context.applicationContext.filesDir
    private var dadb: Dadb? = null

    suspend fun connect(device: TvDevice): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val privateKey = File(keyDirectory, PRIVATE_KEY)
            val publicKey = File(keyDirectory, PUBLIC_KEY)
            if (!privateKey.exists() || !publicKey.exists()) {
                AdbKeyPair.generate(privateKey, publicKey)
                log("Generated ADB authentication key")
            }

            log("Connecting ADB to ${device.hostAddress}:${device.adbPort}")
            val connection = Dadb.create(
                device.hostAddress,
                device.adbPort,
                AdbKeyPair.read(privateKey, publicKey),
                connectTimeout = 5_000,
                socketTimeout = 30_000
            )
            val response = connection.shell("echo hometv-connected")
            if (response.exitCode != 0) {
                connection.close()
                return@withContext Result.failure(
                    IllegalStateException("ADB shell failed: ${response.allOutput}")
                )
            }

            dadb = connection
            log("ADB connected to ${device.hostAddress}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "ADB connection failed", e)
            log("ADB connection failed: ${e.message ?: e.javaClass.simpleName}")
            disconnect()
            Result.failure(e)
        }
    }

    suspend fun sendCommand(command: RemoteCommand): Result<Unit> = withContext(Dispatchers.IO) {
        val connection = dadb ?: return@withContext Result.failure(
            IllegalStateException("ADB is not connected")
        )
        try {
            val shellCommand = when (command) {
                is RemoteCommand.KeyEventCommand -> "input keyevent ${command.keycode}"
                is RemoteCommand.LaunchAppCommand ->
                    "am start -a android.intent.action.VIEW -d ${shellQuote(command.appLink)}"
            }
            val response = connection.shell(shellCommand)
            if (response.exitCode != 0) {
                log("ADB command failed (${response.exitCode}): ${response.allOutput.trim()}")
                Result.failure(IllegalStateException(response.allOutput.trim()))
            } else {
                log("ADB sent ${command.javaClass.simpleName}")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ADB command failed", e)
            log("ADB command failed: ${e.message ?: e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    fun disconnect() {
        runCatching { dadb?.close() }
        dadb = null
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun log(message: String) {
        onLog(message)
    }
}
