package com.example.hometv.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Reports whether a local-capable network (Wi-Fi or Ethernet) is currently
 * available. Mobile-data-only connectivity is ignored because TVs live on the LAN.
 */
class NetworkMonitor(context: Context) {

    private companion object {
        private const val TAG = "HomeTvLog"
    }

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Suppress("DEPRECATION")
    fun isAvailable(): Boolean = try {
        connectivityManager.allNetworks.any { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@any false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not read network state", e)
        false
    }

    val availability: Flow<Boolean> = callbackFlow {
        trySend(isAvailable())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isAvailable())
            }

            override fun onLost(network: Network) {
                trySend(isAvailable())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(isAvailable())
            }
        }

        try {
            connectivityManager.registerNetworkCallback(wifiRequest, callback)
            connectivityManager.registerNetworkCallback(ethernetRequest, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Could not register network callback", e)
            close(e)
        }

        awaitClose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Could not unregister network callback", e)
            }
        }
    }.distinctUntilChanged()

    private val wifiRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    private val ethernetRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        .build()
}
