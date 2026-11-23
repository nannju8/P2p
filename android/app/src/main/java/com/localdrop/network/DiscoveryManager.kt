package com.localdrop.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.coroutines.resume

data class Peer(val id: String, val name: String, val host: InetAddress, val port: Int, val mode: String, val seenAt: Long)

class DiscoveryManager(
    private val context: Context,
    private val binder: NetworkBinder,
    private val scope: CoroutineScope,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers

    private var multicastLock: WifiManager.MulticastLock? = null
    private val jobs = mutableListOf<Job>()
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var announceSocket: DatagramSocket? = null
    private var listenSocket: DatagramSocket? = null

    fun advertise(deviceId: String, name: String, port: Int, mode: String) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = "LocalDrop-$deviceId"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("n", name)
            setAttribute("m", mode)
            setAttribute("id", deviceId)
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }.also { runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, it) } }

        jobs += scope.launch(Dispatchers.IO) {
            val msg = "LDROP1|$deviceId|${name.replace('|', ' ')}|$port|$mode".toByteArray()
            while (isActive) {
                val targets = wifiBroadcastTargets()
                if (targets.isEmpty()) {
                    val sock = announceSocket ?: DatagramSocket().apply { broadcast = true }.also { s ->
                        binder.wifi?.bindSocket(s)
                        announceSocket = s
                    }
                    runCatching { sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), UDP_PORT)) }
                } else {
                    for ((localAddr, bcast) in targets) {
                        runCatching {
                            DatagramSocket(InetSocketAddress(localAddr, 0)).use { s ->
                                s.broadcast = true
                                s.send(DatagramPacket(msg, msg.size, bcast, UDP_PORT))
                            }
                        }
                    }
                }
                delay(1_000)
            }
        }
    }

    fun startDiscovery(selfId: String) {
        stop()
        multicastLock = wifi.createMulticastLock("localdrop-mdns").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        jobs += scope.launch(Dispatchers.IO) {
            for (svc in resolveQueue) {
                if (!isActive) break
                val resolved = withTimeoutOrNull(3_000) { resolveSequentially(svc) } ?: continue
                val id = resolved.attributes["id"]?.decodeToString() ?: continue
                if (id == selfId) continue
                upsert(
                    Peer(
                        id = id,
                        name = resolved.attributes["n"]?.decodeToString() ?: resolved.serviceName,
                        host = resolved.host,
                        port = resolved.port,
                        mode = resolved.attributes["m"]?.decodeToString() ?: "Wi-Fi",
                        seenAt = System.currentTimeMillis()
                    )
                )
            }
        }

        discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) {
                if (s.serviceType.contains("localdrop")) resolveQueue.trySend(s)
            }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }.also { runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, it) } }

        jobs += scope.launch(Dispatchers.IO) {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(UDP_PORT))
            }
            listenSocket = sock
            val buf = ByteArray(512)
            while (isActive && !sock.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    sock.soTimeout = 1_500
                    sock.receive(p)
                } catch (_: Exception) {
                    continue
                }
                val f = String(p.data, 0, p.length).split('|')
                if (f.size == 5 && f[0] == "LDROP1" && f[1] != selfId) {
                    f[3].toIntOrNull()?.let { port ->
                        upsert(Peer(f[1], f[2].take(40), p.address, port, f[4].take(20), System.currentTimeMillis()))
                    }
                }
            }
        }

        jobs += scope.launch {
            while (isActive) {
                delay(2_000)
                val limit = System.currentTimeMillis() - 6_000
                _peers.update { l -> l.filter { it.seenAt > limit } }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveSequentially(service: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(info)
                }
            })
        }

    private fun upsert(p: Peer) = _peers.update { l -> l.filterNot { it.id == p.id } + p }

    private fun wifiBroadcastTargets(): List<Pair<Inet4Address, InetAddress>> {
        val ifaces = binder.wifiNetworkInterfaces().ifEmpty {
            runCatching { java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback } }
                .getOrDefault(emptyList())
        }
        return ifaces.flatMap { it.interfaceAddresses }.mapNotNull { ia ->
            val addr = ia.address as? Inet4Address ?: return@mapNotNull null
            val bcast = ia.broadcast ?: return@mapNotNull null
            addr to bcast
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        runCatching { announceSocket?.close() }
        announceSocket = null
        runCatching { listenSocket?.close() }
        listenSocket = null
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        multicastLock = null
        _peers.value = emptyList()
    }

    companion object {
        const val SERVICE_TYPE = "_localdrop._tcp."
        const val UDP_PORT = 45454
    }
}
