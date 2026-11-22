package com.localdrop.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.content.Intent

data class HotspotInfo(val ssid: String, val password: String) {
    /** Format standard lu par l'appareil photo Android : connexion Wi-Fi en un scan. */
    fun wifiQr(): String = "WIFI:T:WPA;S:${esc(ssid)};P:${esc(password)};;"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
}

/** Point d'accès local universel (LocalOnlyHotspot) + Wi-Fi Direct 5 GHz. Nécessite NEARBY_WIFI_DEVICES / FINE_LOCATION (<13). */
class HotspotController(private val context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val p2p = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var p2pChannel: WifiP2pManager.Channel? = null

    @SuppressLint("MissingPermission")
    fun startLocalOnlyHotspot(onReady: (HotspotInfo) -> Unit, onError: (String) -> Unit) {
        wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            @Suppress("DEPRECATION")
            override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                reservation = r
                val info = if (Build.VERSION.SDK_INT >= 30) {
                    val c = r.softApConfiguration
                    HotspotInfo(c.wifiSsid?.toString()?.trim('"') ?: "", c.passphrase ?: "")
                } else {
                    val c: WifiConfiguration = r.wifiConfiguration!!
                    HotspotInfo(c.SSID.trim('"'), c.preSharedKey.trim('"'))
                }
                onReady(info)
            }
            override fun onFailed(reason: Int) = onError("Point d'accès indisponible (code $reason)")
            override fun onStopped() {}
        }, Handler(Looper.getMainLooper()))
    }

    fun stopHotspot() { reservation?.close(); reservation = null }

    /** Wi-Fi Direct, groupe créé sur la bande 5 GHz quand l'API le permet (≥ 29), repli automatique sinon. */
    @SuppressLint("MissingPermission")
    fun createWifiDirectGroup(onResult: (Boolean) -> Unit) {
        val mgr = p2p ?: return onResult(false)
        val ch = mgr.initialize(context, Looper.getMainLooper(), null).also { p2pChannel = it }
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onResult(true)
            override fun onFailure(reason: Int) = onResult(false)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val cfg = WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-LD-" + (1000..9999).random())
                .setPassphrase(randomPassphrase())
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)   // préfère 5 GHz
                .build()
            mgr.createGroup(ch, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = onResult(true)
                override fun onFailure(reason: Int) = mgr.createGroup(ch, listener) // repli bande auto
            })
        } else mgr.createGroup(ch, listener)
    }

    fun removeWifiDirectGroup() { p2p?.let { m -> p2pChannel?.let { m.removeGroup(it, null) } } }

    private fun randomPassphrase(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val r = java.security.SecureRandom()
        return (1..12).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
    }

    companion object {
        /** Panneaux système : le récepteur active son Wi-Fi en 1 clic (API 29+), sinon écran Paramètres. */
        fun wifiPanelIntent(): Intent =
            if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
        fun tetherSettingsIntent(): Intent = Intent("android.settings.TETHER_SETTINGS")
    }
}
