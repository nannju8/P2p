package com.localdrop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localdrop.files.formatSize
import com.localdrop.session.NetworkBannerState
import com.localdrop.session.WebShareUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareWebScreen(
    state: WebShareUiState,
    network: NetworkBannerState,
    onToggleHotspot: () -> Unit,
    onStartWifiDirect: () -> Unit,
    onClearNetError: () -> Unit,
    onTogglePin: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var showUrlQr by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Partager l'application") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fermer") } }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            NetworkAssistBar(network, onToggleHotspot, onStartWifiDirect, onClearNetError)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1. Connecter l'autre téléphone", fontWeight = FontWeight.Bold)
                    val hs = network.hotspotInfo
                    if (hs != null) {
                        Text("Wi-Fi : ${hs.ssid}")
                        Text("Mot de passe : ${hs.password}")
                    } else {
                        Text("Créez un Point d'accès ci-dessus ou mettez les deux téléphones sur le même Wi-Fi.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Conseil : s'il demande « Rester connecté sans Internet ? », dites OUI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("2. Taper cette adresse dans Chrome", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            state.url.ifBlank { "Démarrage du serveur…" },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f)
                        )
                        if (state.url.isNotBlank()) {
                            IconButton(onClick = { clipboard.setText(AnnotatedString(state.url)) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copier")
                            }
                        }
                    }

                    if (state.url.isNotBlank()) {
                        OutlinedButton(onClick = { showUrlQr = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.QrCode2, contentDescription = null)
                            Spacer(Modifier.padding(4.dp))
                            Text("Afficher le QR Code de l'adresse")
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Protéger par code à 4 chiffres", fontWeight = FontWeight.Medium)
                            if (state.pinEnabled) Text("Code PIN : ${state.pinCode}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Switch(checked = state.pinEnabled, onCheckedChange = onTogglePin)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val st = state.status
                    Text(
                        if (st.running) "Serveur HTTP actif (port ${st.port}) • ${st.downloads} téléchargement(s)"
                        else state.error ?: "Serveur arrêté",
                        fontWeight = FontWeight.SemiBold
                    )
                    if (st.activeBytes > 0 && st.totalBytes > 0) {
                        val p = (st.activeBytes.toFloat() / st.totalBytes.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("${formatSize(st.activeBytes)} / ${formatSize(st.totalBytes)}")
                    }
                }
            }
        }
    }

    if (showUrlQr && state.url.isNotBlank()) {
        QrCodeDialog("Ouvrir dans le navigateur", state.url, state.url) { showUrlQr = false }
    }
}
