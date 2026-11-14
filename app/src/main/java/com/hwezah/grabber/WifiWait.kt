package com.hwezah.grabber

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/** Wi-Fi only: wakes the queue when an unmetered network (Wi-Fi) comes up. */
object WifiWait {
    private var armed = false

    @Synchronized
    fun arm(context: Context) {
        if (armed) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).build()
        runCatching {
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(WifiWait) { armed = false }
                    runCatching { cm.unregisterNetworkCallback(this) }
                    Downloads.kick()
                }
            })
            armed = true
        }
    }
}

/** Any network back (after a drop): wake the queue so waiting retries go now. */
object NetWait {
    private var armed = false

    @Synchronized
    fun arm(context: Context) {
        if (armed) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching {
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(NetWait) { armed = false }
                    runCatching { cm.unregisterNetworkCallback(this) }
                    Downloads.retryNow()
                }
            })
            armed = true
        }
    }
}
