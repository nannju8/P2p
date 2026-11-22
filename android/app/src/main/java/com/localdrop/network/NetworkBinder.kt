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
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume

/**
 * ANTI-DÉTOURNEMENT 4G/5G : sur un point d'accès sans Internet, Android route volontiers les sockets
 * vers les données mobiles. Tous nos sockets sont donc liés EXPLICITEMENT au réseau Wi-Fi.
 */
class NetworkBinder(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Volatile var wifi: Network? = null
        private set
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Demande un réseau Wi-Fi *même sans capacité INTERNET* (hotspot local, Wi-Fi Direct). */
    suspend fun acquireWifi(timeoutMs: Long = 8_000): Network? {
        findActiveWifi()?.let { wifi = it; return it }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { if (cont.isActive) cont.resume(network) }
                }
                callback = cb
                cm.requestNetwork(request, cb)
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
            }
        }?.also { wifi = it }
    }

    private fun findActiveWifi(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** Option « béton » : tout le process passe par le Wi-Fi pendant la session. */
    fun bindProcess(enable: Boolean) { cm.bindProcessToNetwork(if (enable) wifi else null) }

    fun localIpv4(): Inet4Address? = wifi?.let { n ->
        cm.getLinkProperties(n)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()
    }

    /** Socket client lié au Wi-Fi + réglages haut débit. */
    fun connect(host: InetAddress, port: Int, timeoutMs: Int = 5_000, bulk: Boolean = true): Socket {
        val net = checkNotNull(wifi) { "réseau Wi-Fi non acquis" }
        val s = Socket()
        net.bindSocket(s)                       // ← le point crucial
        tune(s, bulk)
        s.connect(InetSocketAddress(host, port), timeoutMs)
        return s
    }

    /** ServerSocket écoutant uniquement sur l'interface Wi-Fi. */
    fun listen(port: Int = 0): ServerSocket {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.receiveBufferSize = BUF
        ss.bind(InetSocketAddress(localIpv4() ?: InetAddress.getByName("0.0.0.0"), port))
        return ss
    }

    fun tune(s: Socket, bulk: Boolean) {
        s.tcpNoDelay = true                      // canal de contrôle : latence minimale
        if (bulk) { s.sendBufferSize = BUF; s.receiveBufferSize = BUF }   // gros tampons SO_SNDBUF / SO_RCVBUF
        s.keepAlive = true
    }

    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null; runCatching { cm.bindProcessToNetwork(null) }
    }

    companion object { const val BUF = 2 * 1024 * 1024 }
}
