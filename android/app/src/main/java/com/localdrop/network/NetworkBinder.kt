package com.localdrop.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume

/**
 * ANTI-DÉTOURNEMENT 4G/5G : force les sockets sur le Wi-Fi.
 * Gère aussi les interfaces Hotspot / Wi-Fi Direct (ap0, swlan0, p2p-wlan0) absentes de ConnectivityManager.
 */
class NetworkBinder(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Volatile var wifi: Network? = null
        private set
    private var callback: ConnectivityManager.NetworkCallback? = null

    suspend fun acquireWifi(timeoutMs: Long = 6_000): Network? {
        findActiveWifi()?.let { wifi = it; return it }
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) }; callback = null }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        wifi = network
                        if (cont.isActive) cont.resume(network)
                    }
                    override fun onLost(network: Network) {
                        if (wifi == network) wifi = findActiveWifi()
                    }
                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                callback = cb
                cm.requestNetwork(request, cb)
            }
        }?.also { wifi = it }
    }

    private fun findActiveWifi(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun bindProcess(enable: Boolean) {
        val target = if (enable) wifi else null
        runCatching { cm.bindProcessToNetwork(target) }
    }

    /** IPv4 locale : d'abord via ConnectivityManager, sinon via interfaces Wi-Fi / Hotspot / P2P. */
    fun localIpv4(): Inet4Address? {
        wifi?.let { n ->
            cm.getLinkProperties(n)?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.let { return it }
        }
        return wifiNetworkInterfaces()
            .flatMap { it.interfaceAddresses }
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
    }

    /** Interfaces locales Wi-Fi / Point d'accès / Wi-Fi Direct actives. */
    fun wifiNetworkInterfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { iface ->
            iface.isUp && !iface.isLoopback && (
                iface.name.startsWith("wlan") ||
                    iface.name.startsWith("ap") ||
                    iface.name.startsWith("swlan") ||
                    iface.name.startsWith("p2p") ||
                    iface.name.startsWith("wifi")
                )
        }
    }.getOrDefault(emptyList())

    fun connect(host: InetAddress, port: Int, timeoutMs: Int = 5_000, bulk: Boolean = true): Socket {
        val s = Socket()
        wifi?.bindSocket(s)
        tune(s, bulk)
        s.connect(InetSocketAddress(host, port), timeoutMs)
        return s
    }

    fun listen(port: Int = 0): ServerSocket {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.receiveBufferSize = BUF
        ss.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
        return ss
    }

    fun tune(s: Socket, bulk: Boolean) {
        s.tcpNoDelay = true
        if (bulk) {
            s.sendBufferSize = BUF
            s.receiveBufferSize = BUF
        }
        s.keepAlive = true
    }

    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        wifi = null
        runCatching { cm.bindProcessToNetwork(null) }
    }

    companion object { const val BUF = 2 * 1024 * 1024 }
}
