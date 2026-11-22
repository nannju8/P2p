package com.localdrop.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

data class Peer(val id: String, val name: String, val host: InetAddress, val port: Int, val mode: String, val seenAt: Long)

/**
 * Double moteur de découverte :
 *  1. NsdManager (mDNS, `_localdrop._tcp`)
 *  2. Broadcast UDP (port 45454) : fallback pour les routeurs qui bloquent le multicast.
 * Seules des métadonnées publiques (nom, port, mode) sont annoncées – JAMAIS le code.
 */
class DiscoveryManager(private val context: Context, private val binder: NetworkBinder, private val scope: CoroutineScope) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers

    private var multicastLock: WifiManager.MulticastLock? = null
    private var jobs = mutableListOf<Job>()
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var announceSocket: DatagramSocket? = null

    // ── Envoyeur : devient découvrable ──────────────────────────────────────────────
    fun advertise(deviceId: String, name: String, port: Int, mode: String) {
        val info = NsdServiceInfo().apply {
            serviceName = "LocalDrop-$deviceId"; serviceType = SERVICE_TYPE; setPort(port)
            setAttribute("n", name); setAttribute("m", mode); setAttribute("id", deviceId)
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }.also { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, it) }

        jobs += scope.launch(Dispatchers.IO) {          // balise UDP toutes les secondes
            val sock = DatagramSocket().apply { broadcast = true }.also { s -> binder.wifi?.bindSocket(s) }
            announceSocket = sock
            val msg = "LDROP1|$deviceId|${name.replace('|', ' ')}|$port|$mode".toByteArray()
            while (isActive) {
                broadcastAddresses().forEach { runCatching { sock.send(DatagramPacket(msg, msg.size, it, UDP_PORT)) } }
                delay(1_000)
            }
        }
    }

    // ── Récepteur : liste temps réel des envoyeurs ────────────────────────────────────
    fun startDiscovery(selfId: String) {
        multicastLock = wifi.createMulticastLock("localdrop-mdns").apply { setReferenceCounted(false); acquire() }

        discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val id = i.attributes["id"]?.decodeToString() ?: return
                        if (id == selfId) return
                        upsert(Peer(id, i.attributes["n"]?.decodeToString() ?: i.serviceName, i.host, i.port,
                            i.attributes["m"]?.decodeToString() ?: "Wi-Fi", System.currentTimeMillis()))
                    }
                })
            }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }.also { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, it) }

        jobs += scope.launch(Dispatchers.IO) {          // écoute des balises UDP
            val sock = DatagramSocket(null).apply { reuseAddress = true; bind(java.net.InetSocketAddress(UDP_PORT)) }
            binder.wifi?.bindSocket(sock)
            val buf = ByteArray(512)
            while (isActive) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { sock.soTimeout = 1500; sock.receive(p) }.onFailure { continue }
                val f = String(p.data, 0, p.length).split('|')
                if (f.size == 5 && f[0] == "LDROP1" && f[1] != selfId)
                    f[3].toIntOrNull()?.let { upsert(Peer(f[1], f[2].take(40), p.address, it, f[4].take(20), System.currentTimeMillis())) }
            }
            sock.close()
        }
        jobs += scope.launch { while (isActive) { delay(2_000); val limit = System.currentTimeMillis() - 6_000
            _peers.update { l -> l.filter { it.seenAt > limit } } } }   // retire les envoyeurs disparus
    }

    private fun upsert(p: Peer) = _peers.update { l -> l.filterNot { it.id == p.id } + p }

    private fun broadcastAddresses(): List<InetAddress> =
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }.filter { it.address is Inet4Address }.mapNotNull { it.broadcast }

    fun stop() {
        jobs.forEach { it.cancel() }; jobs.clear()
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        announceSocket?.close()
        multicastLock?.takeIf { it.isHeld }?.release()
        _peers.value = emptyList()
    }

    companion object { const val SERVICE_TYPE = "_localdrop._tcp."; const val UDP_PORT = 45454 }
}
