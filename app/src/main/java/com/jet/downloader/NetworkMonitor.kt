package com.jet.downloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Reports validated internet connectivity. Call start/stop with the Activity lifecycle.
 */
class NetworkMonitor(
    context: Context,
    private val onStateChanged: (Boolean) -> Unit
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var registered = false

    @Volatile
    private var lastDispatchedState: Boolean? = null

    fun isOnline(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                @Suppress("DEPRECATION")
                val info = connectivityManager.activeNetworkInfo
                @Suppress("DEPRECATION")
                info != null && info.isConnected
            }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun notifyIfChanged(online: Boolean) {
        mainHandler.post {
            if (lastDispatchedState == online) return@post
            lastDispatchedState = online
            onStateChanged(online)
        }
    }

    private fun evaluateAndNotify() = notifyIfChanged(isOnline())

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = evaluateAndNotify()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // Read the active network snapshot so a secondary unvalidated network
            // cannot incorrectly mark an otherwise working connection as offline.
            evaluateAndNotify()
        }

        override fun onLost(network: Network) = evaluateAndNotify()
    }

    @Synchronized
    fun start() {
        if (registered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            registered = true
        } catch (_: Exception) {
            // The initial snapshot still gives the UI the best available state.
        }
        evaluateAndNotify()
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        } finally {
            registered = false
        }
    }
}
