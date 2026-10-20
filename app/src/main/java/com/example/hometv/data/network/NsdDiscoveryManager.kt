package com.example.hometv.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.hometv.domain.model.TvDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Discovers Android TV Remote v2 devices advertised over mDNS.
 *
 * Service type: _androidtvremote2._tcp (the same service used by the Google TV app).
 */
class NsdDiscoveryManager(
    context: Context,
    private val onLog: (String) -> Unit = {}
) {

    private companion object {
        private const val TAG = "HomeTvLog"
        private const val SERVICE_TYPE = "_androidtvremote2._tcp"
        private const val PAIRING_PORT = 6467
        private const val COMMAND_PORT = 6466
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun discoverDevices(): Flow<List<TvDevice>> = callbackFlow {
        log("mDNS scan starting for $SERVICE_TYPE")
        logNetworkState()

        var multicastLock: WifiManager.MulticastLock? = null
        try {
            multicastLock = wifiManager.createMulticastLock("HomeTvMulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }
            log("Multicast lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Could not acquire multicast lock", e)
            log("Multicast lock failed: ${e.message}")
        }

        val discovered = linkedMapOf<String, TvDevice>()
        val pending = ArrayDeque<NsdServiceInfo>()
        val retries = mutableMapOf<String, Int>()
        val queueLock = Any()
        var resolving = false
        val flowScope = this

        trySend(emptyList())

        lateinit var resolveNext: () -> Unit

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(queueLock) { resolving = false }
                val key = serviceInfo.serviceName
                val attempt = (retries[key] ?: 0) + 1
                retries[key] = attempt
                log("Resolve failed for '${serviceInfo.serviceName}': ${errorName(errorCode)} (attempt $attempt)")
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempt <= 3) {
                    synchronized(queueLock) { pending.addFirst(serviceInfo) }
                    flowScope.launch {
                        delay(300)
                        resolveNext()
                    }
                } else {
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                synchronized(queueLock) { resolving = false }
                val host = serviceInfo.host?.hostAddress
                if (host.isNullOrBlank()) {
                    log("Resolved '${serviceInfo.serviceName}' without a host address")
                    resolveNext()
                    return
                }

                val device = TvDevice(
                    id = "mdns:$host",
                    name = serviceInfo.serviceName,
                    hostAddress = host,
                    pairingPort = PAIRING_PORT,
                    commandPort = COMMAND_PORT,
                    isPaired = false,
                    isOnline = true,
                    serviceType = serviceInfo.serviceType,
                    lastSeen = System.currentTimeMillis()
                )

                synchronized(queueLock) {
                    discovered[host] = device
                    val list = discovered.values.sortedBy { it.name }
                    trySend(list)
                    log("Found TV '${device.name}' at $host (advertised port ${serviceInfo.port}), total=${list.size}")
                }
                resolveNext()
            }
        }

        resolveNext = {
            val next = synchronized(queueLock) {
                if (resolving || pending.isEmpty()) {
                    null
                } else {
                    resolving = true
                    pending.removeFirst()
                }
            }
            if (next != null) {
                try {
                    log("Resolving '${next.serviceName}'")
                    nsdManager.resolveService(next, resolveListener)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start resolve for '${next.serviceName}'", e)
                    log("Resolve error for '${next.serviceName}': ${e.message}")
                    synchronized(queueLock) { resolving = false }
                    flowScope.launch {
                        delay(300)
                        resolveNext()
                    }
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                log("mDNS discovery started for $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                log("Service found: '${serviceInfo.serviceName}' (${serviceInfo.serviceType})")
                if (!serviceInfo.serviceType.contains("_androidtvremote2")) {
                    log("Ignoring unsupported service type ${serviceInfo.serviceType}")
                    return
                }
                synchronized(queueLock) { pending.addLast(serviceInfo) }
                resolveNext()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                log("Service lost: '${serviceInfo.serviceName}'")
                synchronized(queueLock) {
                    discovered.entries.removeIf { it.value.name == serviceInfo.serviceName }
                    val list = discovered.values.sortedBy { it.name }
                    trySend(list)
                    log("Devices after loss (${list.size}): ${list.joinToString { "${it.name} [${it.hostAddress}]" }}")
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                log("mDNS discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                val message = "mDNS discovery failed to start: ${errorName(errorCode)}"
                Log.e(TAG, message)
                log(message)
                close(IllegalStateException(message))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("mDNS discovery failed to stop: ${errorName(errorCode)}")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            log("discoverServices() requested")
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices() threw", e)
            log("discoverServices() error: ${e.message}")
            close(e)
        }

        val ticker = launch {
            var elapsedSeconds = 0
            while (isActive) {
                delay(5_000)
                elapsedSeconds += 5
                val list = synchronized(queueLock) { discovered.values.toList() }
                log("Scan active (${elapsedSeconds}s): ${list.size} device(s)${formatDevices(list)}")
            }
        }

        awaitClose {
            ticker.cancel()
            log("mDNS scan stopping")
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
                log("Error stopping discovery: ${e.message}")
            }
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                    log("Multicast lock released")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing multicast lock", e)
            }
        }
    }

    private fun logNetworkState() {
        try {
            val active = connectivityManager.activeNetwork
            val capabilities = active?.let { connectivityManager.getNetworkCapabilities(it) }
            val onWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val onEthernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            log(
                "Network: wifiEnabled=${wifiManager.isWifiEnabled}, " +
                    "activeWifi=$onWifi, activeEthernet=$onEthernet"
            )
        } catch (e: Exception) {
            log("Network state unavailable: ${e.message}")
        }
    }

    private fun errorName(code: Int): String = when (code) {
        NsdManager.FAILURE_ALREADY_ACTIVE -> "FAILURE_ALREADY_ACTIVE($code)"
        NsdManager.FAILURE_INTERNAL_ERROR -> "FAILURE_INTERNAL_ERROR($code)"
        NsdManager.FAILURE_MAX_LIMIT -> "FAILURE_MAX_LIMIT($code)"
        else -> "error $code"
    }

    private fun formatDevices(devices: List<TvDevice>): String {
        if (devices.isEmpty()) return ""
        return ": " + devices.joinToString { "${it.name} [${it.hostAddress}]" }
    }

    private fun log(message: String) {
        onLog(message)
    }
}
