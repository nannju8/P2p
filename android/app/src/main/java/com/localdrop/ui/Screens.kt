package com.localdrop.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localdrop.network.HotspotController
import com.localdrop.network.Peer
import com.localdrop.protocol.PairingGuard
import kotlinx.coroutines.delay

/** Écran 1 – Accueil : ENVOYER / RECEVOIR / PARTAGER L'APPLICATION. */
@Composable
fun HomeScreen(onSend: () -> Unit, onReceive: () -> Unit, onShareApp: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("LocalDrop", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Button(onClick = onSend, Modifier.fillMaxWidth().height(96.dp)) { Text("ENVOYER", fontSize = 24.sp) }
        Button(onClick = onReceive, Modifier.fillMaxWidth().height(96.dp)) { Text("RECEVOIR", fontSize = 24.sp) }
        OutlinedButton(onClick = onShareApp, Modifier.fillMaxWidth()) { Text("PARTAGER L'APPLICATION") }
    }
}

/** Écran 2 – Barre contextuelle, persistante et non bloquante, reflétant l'état RÉEL du téléphone. */
@Composable
fun NetworkAssistBar(onStartHotspot: () -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf(readWifi(ctx)) }
    LaunchedEffect(Unit) { while (true) { status = readWifi(ctx); delay(1500) } }
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status.connected) {
                Text("Wi-Fi actif (${status.ssid}) – Prêt", Modifier.weight(1f))
                TextButton(onClick = onStartHotspot) { Text("Point d'accès") }
            } else {
                Text("Aucun Wi-Fi commun", Modifier.weight(1f))
                TextButton(onClick = onStartHotspot) { Text("Créer un point d'accès") }
                TextButton(onClick = { ctx.startActivity(HotspotController.wifiPanelIntent()) }) { Text("Activer le Wi-Fi") }
            }
        }
    }
}

private data class WifiStatus(val connected: Boolean, val ssid: String)

@Suppress("DEPRECATION")
private fun readWifi(ctx: Context): WifiStatus {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val wifiNet = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    return WifiStatus(wifiNet != null, wm.connectionInfo?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" } ?: "réseau local")
}

/** Écran 4 (envoyeur) – code à 4 chiffres généré automatiquement, minuteur 5 min, « Nouveau code ». */
@Composable
fun PairingSenderScreen(onBack: () -> Unit, guard: PairingGuard = remember { PairingGuard() }) {
    val code by guard.displayedCode.collectAsState()
    var remaining by remember { mutableStateOf(guard.remainingMs()) }
    LaunchedEffect(code) { while (true) { remaining = guard.remainingMs(); if (remaining == 0L) guard.rotate(); delay(500) } }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        NetworkAssistBar(onStartHotspot = {})
        Text("Saisissez ce code sur le téléphone récepteur")
        Text(code.chunked(1).joinToString(" "), fontSize = 72.sp, fontWeight = FontWeight.Black)
        Text("Expire dans %d:%02d".format(remaining / 60000, remaining / 1000 % 60))
        Button(onClick = { guard.rotate() }) { Text("Nouveau code") }
        TextButton(onClick = onBack) { Text("Annuler") }
    }
}

/** Écran 4 (récepteur) – liste temps réel des envoyeurs, puis pavé numérique. */
@Composable
fun PairingReceiverScreen(onBack: () -> Unit, peers: List<Peer> = emptyList(), onSubmit: (Peer, String) -> Unit = { _, _ -> }) {
    var selected by remember { mutableStateOf<Peer?>(null) }
    var typed by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NetworkAssistBar(onStartHotspot = {})
        val p = selected
        if (p == null) {
            Text("Appareils à proximité", style = MaterialTheme.typography.titleLarge)
            LazyColumn { items(peers) { Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { TextButton(onClick = { selected = it }) { Text("${it.name} · ${it.mode}") } } } }
        } else {
            Text("Code affiché sur « ${p.name} »", style = MaterialTheme.typography.titleLarge)
            Text(typed.padEnd(4, '•').chunked(1).joinToString(" "), fontSize = 56.sp, fontWeight = FontWeight.Bold)
            listOf("123", "456", "789", "C0⌫").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k -> OutlinedButton(onClick = {
                        when (k) { 'C' -> typed = ""; '⌫' -> typed = typed.dropLast(1); else -> if (typed.length < 4) typed += k }
                        if (typed.length == 4) onSubmit(p, typed)
                    }, Modifier.weight(1f).height(64.dp)) { Text(k.toString(), fontSize = 24.sp) } }
                }
            }
        }
        TextButton(onClick = onBack) { Text("Retour") }
    }
}
