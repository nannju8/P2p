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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localdrop.data.AppSettings
import com.localdrop.data.TransferRecord
import com.localdrop.files.formatSize
import com.localdrop.protocol.FileReceiver
import com.localdrop.ui.theme.ThemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settings: AppSettings,
    lastRecord: TransferRecord?,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onShareApp: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onRenameDevice: (String) -> Unit,
) {
    var showRename by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LocalDrop", fontWeight = FontWeight.Bold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showRename = true }
                        ) {
                            Text(
                                settings.deviceName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Renommer",
                                modifier = Modifier.padding(start = 4.dp).size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Default.History, contentDescription = "Historique") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Paramètres") }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = onSend,
                modifier = Modifier.fillMaxWidth().height(104.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(30.dp))
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("ENVOYER", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Vidéos, audios, archives, dossiers…", style = MaterialTheme.typography.bodySmall)
                }
            }

            Button(
                onClick = onReceive,
                modifier = Modifier.fillMaxWidth().height(104.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(30.dp))
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("RECEVOIR", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Trouver l'envoyeur et saisir le code", style = MaterialTheme.typography.bodySmall)
                }
            }

            OutlinedButton(
                onClick = onShareApp,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.QrCode2, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Text("Partager l'application (via navigateur)", fontWeight = FontWeight.SemiBold)
            }

            lastRecord?.let { r ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenHistory() },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(
                            if (r.success) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (r.success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                        )
                        Column(Modifier.weight(1f)) {
                            Text("Dernier transfert • ${if (r.isSender) "Envoyé à" else "Reçu de"} ${r.peerName}", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "${r.fileCount} fichier(s) • ${formatSize(r.totalBytes)} • %.1f Mo/s".format(r.speedBps / 1048576.0),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.size(6.dp))
                Text(
                    "100% Hors-ligne • J-PAKE + AES-256-GCM + SHA-256",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showRename) {
        var text by remember { mutableStateOf(settings.deviceName) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Nom de cet appareil") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { Button(onClick = { onRenameDevice(text); showRename = false }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Annuler") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(records: List<TransferRecord>, onClear: () -> Unit, onBack: () -> Unit) {
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historique") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") } },
                actions = {
                    if (records.isNotEmpty()) {
                        IconButton(onClick = onClear) { Icon(Icons.Default.Delete, contentDescription = "Vider") }
                    }
                }
            )
        }
    ) { pad ->
        if (records.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Aucun transfert enregistré.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(records, key = { it.id }) { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(if (r.isSender) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, contentDescription = null)
                            Column(Modifier.weight(1f)) {
                                Text("${if (r.isSender) "Vers" else "De"} ${r.peerName}", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${r.fileCount} fichier(s) • ${formatSize(r.totalBytes)} • %.1f Mo/s".format(r.speedBps / 1048576.0),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (r.summaryPath.isNotBlank()) {
                                    Text(r.summaryPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(fmt.format(Date(r.timestampMs)), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onRename: (String) -> Unit,
    onChangeDest: (String) -> Unit,
    onChangeTheme: (ThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var name by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }
    var dest by remember(settings.destinationPath) { mutableStateOf(settings.destinationPath) }
    val defaultPath = remember(ctx) { FileReceiver.resolveDestinationDir(ctx).path }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") } }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom de l'appareil") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton(onClick = { onRename(name) }) { Text("OK") } }
            )

            OutlinedTextField(
                value = dest,
                onValueChange = { dest = it },
                label = { Text("Dossier de réception (vide = défaut)") },
                placeholder = { Text(defaultPath) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton(onClick = { onChangeDest(dest) }) { Text("OK") } }
            )

            Text("Thème", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.Dark to "Sombre", ThemeMode.Light to "Clair", ThemeMode.System to "Système").forEach { (mode, label) ->
                    FilterChip(selected = settings.themeMode == mode, onClick = { onChangeTheme(mode) }, label = { Text(label) })
                }
            }
        }
    }
}
