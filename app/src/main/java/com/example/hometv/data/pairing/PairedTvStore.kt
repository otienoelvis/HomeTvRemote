package com.example.hometv.data.pairing

import android.content.Context
import android.util.Log
import com.example.hometv.domain.model.PairedTv
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists paired TVs as a small JSON list in SharedPreferences.
 * The key is the TV certificate fingerprint, so the record survives IP changes.
 */
class PairedTvStore(context: Context) {

    private companion object {
        private const val TAG = "HomeTvLog"
        private const val PREFS = "hometv_paired_tvs"
        private const val KEY = "devices"
    }

    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun getAll(): List<PairedTv> {
        val raw = preferences.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val host = item.optString("host")
                    if (id.isBlank() || host.isBlank()) continue
                    add(
                        PairedTv(
                            id = id,
                            name = item.optString("name", "Android TV"),
                            hostAddress = host,
                            pairingPort = item.optInt("pairingPort", 6467),
                            commandPort = item.optInt("commandPort", 6466),
                            adbPort = item.optInt("adbPort", 5555),
                            pairedAt = item.optLong("pairedAt", 0L),
                            lastSeen = item.optLong("lastSeen", 0L)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read paired TV list", e)
            emptyList()
        }
    }

    @Synchronized
    fun upsert(device: PairedTv): List<PairedTv> {
        val updated = getAll().filterNot { it.id == device.id }.toMutableList()
        updated.add(device)
        save(updated)
        Log.i(TAG, "Remembered TV ${device.name} (${device.hostAddress}), total=${updated.size}")
        return updated
    }

    @Synchronized
    fun remove(id: String): List<PairedTv> {
        val updated = getAll().filterNot { it.id == id }
        save(updated)
        Log.i(TAG, "Removed paired TV $id, remaining=${updated.size}")
        return updated
    }

    @Synchronized
    fun updateLastSeen(id: String, hostAddress: String, name: String?): List<PairedTv> {
        val updated = getAll().map { device ->
            if (device.id == id) {
                device.copy(
                    hostAddress = hostAddress,
                    name = name?.takeIf { it.isNotBlank() } ?: device.name,
                    lastSeen = System.currentTimeMillis()
                )
            } else {
                device
            }
        }
        save(updated)
        return updated
    }

    private fun save(devices: List<PairedTv>) {
        val array = JSONArray()
        devices.forEach { device ->
            array.put(
                JSONObject().apply {
                    put("id", device.id)
                    put("name", device.name)
                    put("host", device.hostAddress)
                    put("pairingPort", device.pairingPort)
                    put("commandPort", device.commandPort)
                    put("adbPort", device.adbPort)
                    put("pairedAt", device.pairedAt)
                    put("lastSeen", device.lastSeen)
                }
            )
        }
        preferences.edit().putString(KEY, array.toString()).apply()
    }
}
