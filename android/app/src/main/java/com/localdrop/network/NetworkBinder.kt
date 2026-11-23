package com.localdrop.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class NetworkBinder(context: Context) {
    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Volatile var wifi: Network? = null
        private set
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var specifierCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var specifierNetwork: Network? = null

    suspend fun acquireWifi(timeoutMs: Long = 6_000): Network? {
        specifierNetwork?.let { wifi = it; return it }
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
                        if (wifi == network) wifi = specifierNetwork ?: findActiveWifi()
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

    /** Connexion automatique au Point d'accès scanné par QR Code (zéro mot de passe à taper). */
    @SuppressLint("MissingPermission")
    fun connectToWifi(ssid: String, password: String, onResult: (Boolean, String?) -> Unit) {
        if (ssid.isBlank()) {
            onResult(false, "QR Wi-Fi invalide")
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            specifierCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            specifierCallback = null
            specifierNetwork = null

            val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (password.isNotEmpty()) specBuilder.setWpa2Passphrase(password)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specBuilder.build())
                .build()

            val done = AtomicBoolean(false)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    specifierNetwork = network
                    wifi = network
                    bindProcess(true)
                    if (done.compareAndSet(false, true)) onResult(true, null)
                }
                override fun onUnavailable() {
                    if (done.compareAndSet(false, true)) onResult(false, "Connexion Wi-Fi annulée ou impossible")
                }
                override fun onLost(network: Network) {
                    if (specifierNetwork == network) {
                        specifierNetwork = null
                        if (wifi == network) wifi = findActiveWifi()
                    }
                }
            }
            specifierCallback = cb
            cm.requestNetwork(request, cb, 30_000)
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (!wm.isWifiEnabled) wm.isWifiEnabled = true
                val cfg = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    if (password.isEmpty()) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    else preSharedKey = "\"$password\""
                }
                val id = wm.addNetwork(cfg)
                if (id != -1 && wm.enableNetwork(id, true)) {
                    wm.reconnect()
                    onResult(true, null)
                } else {
                    onResult(false, "Impossible de rejoindre $ssid")
                }
            }.onFailure { onResult(false, it.message) }
        }
    }

    private fun findActiveWifi(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun bindProcess(enable: Boolean) {
        val target = if (enable) (specifierNetwork ?: wifi) else null
        runCatching { cm.bindProcessToNetwork(target) }
    }

    fun localIpv4(): Inet4Address? {
        (specifierNetwork ?: wifi)?.let { n ->
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
        (specifierNetwork ?: wifi)?.bindSocket(s)
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
        specifierCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        specifierCallback = null
        specifierNetwork = null
        wifi = null
        runCatching { cm.bindProcessToNetwork(null) }
    }

    companion object { const val BUF = 2 * 1024 * 1024 }
}
