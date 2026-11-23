package com.localdrop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localdrop.files.formatSize
import com.localdrop.network.Peer
import com.localdrop.session.FileItemStatus
import com.localdrop.session.NetworkBannerState
import com.localdrop.session.TransferSessionUi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderPairingScreen(
    code: String,
    remainingMs: Long,
    feedback: String?,
    network: NetworkBannerState,
    onToggleHotspot: () -> Unit,
    onStartWifiDirect: () -> Unit,
    onClearNetError: () -> Unit,
    onRotateCode: () -> Unit,
    onCancel: () -> Unit,
) {
    var showWifiQr by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("En attente du récepteur") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Annuler") } }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            NetworkAssistBar(network, onToggleHotspot, onStartWifiDirect, onClearNetError)

            if (network.hotspotInfo != null) {
                Button(onClick = { showWifiQr = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCode2, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text("Afficher le QR Code Wi-Fi (pour le récepteur)")
                }
            }

            Text("Saisissez ce code sur le téléphone récepteur", style = MaterialTheme.typography.titleMedium)

            Card(shape = RoundedCornerShape(20.dp)) {
                Text(
                    text = code.chunked(1).joinToString("  "),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp)
                )
            }

            Text(
                "Expire dans %d:%02d".format(remainingMs / 60000, (remainingMs / 1000) % 60),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(onClick = onRotateCode) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text("Nouveau code")
            }

            feedback?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                Text("Appairage sécurisé J-PAKE actif…")
            }
        }
    }

    val hs = network.hotspotInfo
    if (showWifiQr && hs != null) {
        QrCodeDialog(
            title = "Scanner avec l'autre téléphone",
            subtitle = "Réseau : ${hs.ssid}",
            content = hs.wifiQr(),
            onDismiss = { showWifiQr = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverPairingScreen(
    peers: List<Peer>,
    feedback: String?,
    network: NetworkBannerState,
    onToggleHotspot: () -> Unit,
    onStartWifiDirect: () -> Unit,
    onClearNetError: () -> Unit,
    onScanWifiQr: (String) -> Unit,
    onSubmitCode: (Peer, String) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedPeer by remember { mutableStateOf<Peer?>(null) }
    var typed by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedPeer?.let { "Code de ${it.name}" } ?: "Trouver l'envoyeur") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedPeer != null) { selectedPeer = null; typed = "" } else onCancel()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NetworkAssistBar(network, onToggleHotspot, onStartWifiDirect, onClearNetError)

            val peer = selectedPeer
            if (peer == null) {
                // Gros bouton Scanner QR juste ici !
                Button(
                    onClick = { showScanner = true },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.padding(6.dp))
                    Text("Scanner le QR Wi-Fi de l'envoyeur", fontWeight = FontWeight.SemiBold)
                }

                Text("Envoyeurs détectés à proximité :", style = MaterialTheme.typography.titleMedium)
                if (peers.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        Text("Recherche sur le Wi-Fi local…")
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(peers, key = { it.id }) { p ->
                            Card(Modifier.fillMaxWidth().clickable { selectedPeer = p; typed = "" }) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, fontWeight = FontWeight.Bold)
                                        Text("${p.mode} • ${p.host.hostAddress}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("Choisir", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Tapez le code à 4 chiffres affiché sur « ${peer.name} »")
                    Text(
                        typed.padEnd(4, '•').chunked(1).joinToString("  "),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    feedback?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

                    listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("C", "0", "DEL")).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { key ->
                                OutlinedButton(
                                    onClick = {
                                        when (key) {
                                            "C" -> typed = ""
                                            "DEL" -> typed = typed.dropLast(1)
                                            else -> if (typed.length < 4) {
                                                typed += key
                                                if (typed.length == 4) {
                                                    val code = typed
                                                    typed = ""
                                                    onSubmitCode(peer, code)
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(58.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    if (key == "DEL") Icon(Icons.Default.Backspace, contentDescription = "Effacer")
                                    else Text(key, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showScanner) {
        QrScannerDialog(
            title = "Scanner le QR Wi-Fi",
            onScanned = { raw -> showScanner = false; onScanWifiQr(raw) },
            onDismiss = { showScanner = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferringScreen(
    state: TransferSessionUi,
    onCancel: () -> Unit,
    onSendMore: () -> Unit,
    onFinish: () -> Unit,
) {
    val pct = if (state.totalBytes > 0) ((state.doneBytes * 100) / state.totalBytes).toInt().coerceIn(0, 100) else 0

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (state.isSender) "Envoi vers ${state.peerName}" else "Réception de ${state.peerName}") }) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        state.waitingNextBatch && state.files.isEmpty() -> Text("Connecté et chiffré ! En attente des fichiers…")
                        state.cancelled -> Text("Transfert annulé.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        state.errorMessage != null -> Text("Erreur : ${state.errorMessage}", color = MaterialTheme.colorScheme.error)
                        state.completed -> Text(
                            "Transfert terminé à %.1f Mo/s • Intégrité SHA-256 vérifiée".format(state.speedBps / 1048576.0),
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                        else -> Text(
                            "%.1f Mo/s • %d %%".format(state.speedBps / 1048576.0, pct),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    if (state.totalBytes > 0) {
                        LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${formatSize(state.doneBytes)} / ${formatSize(state.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                            if (state.running && state.etaSeconds >= 0) {
                                Text("${state.etaSeconds} s restantes", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    if (!state.isSender && state.savedDirPath.isNotBlank()) {
                        Text("Dossier : ${state.savedDirPath}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.files, key = { it.id }) { f ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text("${formatSize(f.doneBytes)} / ${formatSize(f.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                            }
                            when (f.status) {
                                FileItemStatus.Done -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                FileItemStatus.Error -> Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                FileItemStatus.Active -> {
                                    val fp = if (f.totalBytes > 0) ((f.doneBytes * 100) / f.totalBytes).toInt() else 0
                                    Text("$fp %", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                FileItemStatus.Waiting -> Text("En attente", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            if (state.running && !state.waitingNextBatch) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                ) { Text("Annuler le transfert") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    if (state.isSender && state.completed) {
                        OutlinedButton(onClick = onSendMore, modifier = Modifier.weight(1f)) { Text("Envoyer d'autres") }
                    }
                    Button(onClick = onFinish, modifier = Modifier.weight(1f)) { Text("Terminer") }
                }
            }
        }
    }
}
