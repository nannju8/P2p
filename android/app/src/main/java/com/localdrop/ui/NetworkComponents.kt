package com.localdrop.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.localdrop.network.HotspotController
import com.localdrop.session.NetworkBannerState

fun requiredWifiPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 33) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}.toTypedArray()

fun hasWifiPermissions(ctx: Context): Boolean {
    val needed = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    return ContextCompat.checkSelfPermission(ctx, needed) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun NetworkAssistBar(
    network: NetworkBannerState,
    onToggleHotspot: () -> Unit,
    onStartWifiDirect: () -> Unit,
    onClearError: () -> Unit,
) {
    val ctx = LocalContext.current
    var showExplainDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showQr by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasWifiPermissions(ctx)) pendingAction?.invoke()
        pendingAction = null
    }

    fun runWithWifiPermission(action: () -> Unit) {
        if (hasWifiPermissions(ctx)) action()
        else {
            pendingAction = action
            showExplainDialog = true
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (network.connectingWifi) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (network.hotspotInfo != null) Icons.Default.WifiTethering else Icons.Default.Wifi,
                        contentDescription = null,
                        tint = if (network.wifiConnected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            network.connectingWifi -> "Connexion au Wi-Fi en cours…"
                            network.hotspotInfo != null -> "Point d'accès actif : ${network.hotspotInfo.ssid}"
                            network.wifiConnected -> "Wi-Fi actif (${network.wifiName.ifBlank { "réseau local" }})"
                            else -> "Aucun Wi-Fi connecté"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    val sub = buildString {
                        if (network.hotspotInfo != null) append("Affichez le QR Code Wi-Fi ci-contre pour éviter le mot de passe")
                        else if (network.localIp.isNotBlank()) append("IP locale : ${network.localIp}")
                        else append("Activez le Wi-Fi, créez un Point d'accès ou scannez un QR")
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (network.hotspotInfo != null) {
                    Button(onClick = { showQr = true }) {
                        Icon(Icons.Default.QrCode2, contentDescription = "QR Wi-Fi", modifier = Modifier.size(18.dp))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { runWithWifiPermission(onToggleHotspot) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (network.hotspotInfo != null) "Couper Point d'accès" else "Point d'accès", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { ctx.startActivity(HotspotController.wifiPanelIntent()) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Ouvrir Wi-Fi", maxLines = 1)
                }
            }

            if (!network.wifiConnected && !network.wifiDirectActive) {
                TextButton(
                    onClick = { runWithWifiPermission(onStartWifiDirect) },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Ou lancer Wi-Fi Direct 5 GHz")
                }
            }

            network.error?.let { err ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearError) { Text("OK") }
                }
            }
        }
    }

    if (showExplainDialog) {
        AlertDialog(
            onDismissRequest = { showExplainDialog = false },
            icon = { Icon(Icons.Default.Security, contentDescription = null) },
            title = { Text("Autorisation Wi-Fi local") },
            text = {
                Text(
                    "Android exige cette permission uniquement pour détecter le téléphone voisin ou créer le point d'accès local.\n\nAucune position GPS n'est jamais lue ni envoyée."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showExplainDialog = false
                    permLauncher.launch(requiredWifiPermissions())
                }) { Text("Autoriser") }
            },
            dismissButton = {
                TextButton(onClick = { showExplainDialog = false; pendingAction = null }) { Text("Plus tard") }
            }
        )
    }

    val hs = network.hotspotInfo
    if (showQr && hs != null) {
        QrCodeDialog(
            title = "Scanner pour rejoindre le Wi-Fi",
            subtitle = "Réseau : ${hs.ssid}\nMot de passe : ${hs.password}",
            content = hs.wifiQr(),
            onDismiss = { showQr = false }
        )
    }
}

@Composable
fun QrCodeDialog(title: String, subtitle: String, content: String, onDismiss: () -> Unit) {
    val bmp: Bitmap? = remember(content) { QrHelper.generate(content, 520) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                bmp?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = title,
                        modifier = Modifier
                            .size(220.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Fermer") } }
    )
}
