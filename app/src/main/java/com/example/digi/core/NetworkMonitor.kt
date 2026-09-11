package com.example.digi.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Is there a network path right now, and what kind?
 *
 * Used for two things and nothing else: labelling telemetry (`networkType`), and knowing the moment
 * a link returns so the offline queues can flush immediately rather than waiting out the rest of a
 * 60-second heartbeat interval. A station uplink that flaps every few minutes would otherwise leave
 * an hour of proof-of-play sitting on disk through windows where it could have been delivered.
 *
 * This deliberately reports *link* availability, not reachability of the AMS server. The
 * authoritative answer to "can I talk to the CMS" is a heartbeat that succeeds; this is only a
 * cheap hint about when it is worth trying.
 */
class NetworkMonitor(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
    }

    fun start() {
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        }.onFailure { AppLog.w(TAG, "Could not register network callback", it) }
        refresh()
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun refresh() {
        val now = currentlyOnline()
        if (_online.value != now) {
            AppLog.i(TAG, if (now) "Network restored (${networkType()})" else "Network lost")
            _online.value = now
        }
    }

    fun currentlyOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** The label sent as `telemetry.networkType`. Ethernet matters here — most fixed signage boxes
     *  are wired, and an operator chasing a flapping screen wants to know which it is. */
    fun networkType(): String {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
