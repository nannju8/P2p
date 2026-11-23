package com.localdrop.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.localdrop.files.ExplorerItem
import com.localdrop.files.FileCategory
import com.localdrop.files.SortField
import com.localdrop.files.StorageScanner
import com.localdrop.files.StorageVolumeInfo
import com.localdrop.files.formatSize
import com.localdrop.session.PickerUiState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerScreen(
    state: PickerUiState,
    onRefresh: () -> Unit,
    onSelectCategory: (FileCategory) -> Unit,
    onSelectVolume: (StorageVolumeInfo) -> Unit,
    onOpenDir: (File) -> Unit,
    onNavigateUp: () -> Boolean,
    onQueryChange: (String) -> Unit,
    onToggleSort: (SortField) -> Unit,
    onToggleItem: (ExplorerItem) -> Unit,
    onSafFilesPicked: (List<Uri>) -> Unit,
    onSafTreePicked: (Uri) -> Unit,
    onClearCart: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) onRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val mediaPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onRefresh()
    }
    val safFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onSafFilesPicked(it) }
    val safTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(onSafTreePicked) }

    // Déclenche la vraie fenêtre Android de permission Médias/Fichiers la première fois si non accordée
    var askedMediaOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!askedMediaOnce && !StorageScanner.hasMediaAccess(ctx)) {
            askedMediaOnce = true
            mediaPermLauncher.launch(StorageScanner.requiredMediaPermissions())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choisir des fichiers") },
                navigationIcon = {
                    IconButton(onClick = { if (!onNavigateUp()) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    TextButton(onClick = { safFilesLauncher.launch(arrayOf("*/*")) }) { Text("+ Fichier") }
                    TextButton(onClick = { safTreeLauncher.launch(null) }) { Text("+ Dossier") }
                }
            )
        },
        bottomBar = {
            if (state.cartCount > 0) {
                Surface(tonalElevation = 6.dp, shadowElevation = 8.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${state.cartCount} élément(s) sélectionné(s)", fontWeight = FontWeight.Bold)
                            val sizeTxt = if (state.cartTotalBytes > 0) formatSize(state.cartTotalBytes) else "Dossiers/fichiers prêts"
                            Text(sizeTxt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        TextButton(onClick = onClearCart) { Text("Vider") }
                        Button(onClick = onContinue) { Text("Continuer") }
                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (!state.hasMediaAccess || (!state.hasAllFilesAccess && state.canUseAllFilesScreen)) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (!state.hasMediaAccess) {
                                "Autorisez l'accès aux médias pour afficher vos vidéos, musiques et photos."
                            } else {
                                "Médias autorisés ! Pour naviguer aussi dans tous les dossiers (.zip, .rar, .pdf), activez l'accès complet (ou utilisez + Fichier / + Dossier)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!state.hasMediaAccess) {
                                Button(onClick = { mediaPermLauncher.launch(StorageScanner.requiredMediaPermissions()) }) {
                                    Text("Autoriser Médias")
                                }
                            }
                            if (!state.hasAllFilesAccess && state.canUseAllFilesScreen && Build.VERSION.SDK_INT >= 30) {
                                OutlinedButton(onClick = {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                                    runCatching { ctx.startActivity(intent) }.onFailure {
                                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                    }
                                }) { Text("Accès complet") }
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text("Rechercher…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, contentDescription = "Effacer") }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FileCategory.entries.forEach { cat ->
                    FilterChip(selected = state.category == cat, onClick = { onSelectCategory(cat) }, label = { Text(cat.label) })
                }
            }

            if (state.category == FileCategory.MEMORY) {
                if (state.volumes.size > 1) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.volumes.forEach { vol ->
                            FilterChip(
                                selected = state.selectedVolume?.id == vol.id,
                                onClick = { onSelectVolume(vol) },
                                leadingIcon = { Icon(if (vol.isRemovable) Icons.Default.SdCard else Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text("${vol.label} (${formatSize(vol.freeBytes)} libres)") }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        state.currentDir?.path ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.currentDir?.canonicalPath != state.selectedVolume?.root?.canonicalPath) {
                        TextButton(onClick = { onNavigateUp() }) { Text("↑ Dossier parent") }
                    }
                }
            }

            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortField.entries.forEach { sf ->
                    val arrow = if (state.sortField == sf) (if (state.ascending) " ↑" else " ↓") else ""
                    OutlinedButton(onClick = { onToggleSort(sf) }) { Text("${sf.label}$arrow") }
                }
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.isProtectedDir -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(36.dp))
                        Text("Dossier protégé par Android.", fontWeight = FontWeight.Medium)
                        Button(onClick = { safTreeLauncher.launch(null) }) { Text("Ouvrir via + Dossier") }
                    }
                }
                state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Aucun élément visible ici. Utilisez + Fichier ou + Dossier en haut à droite.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.items, key = { it.key }) { item ->
                        val checked = state.cart.containsKey(item.key)
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (item.isDirectory && !item.isProtected && item.file != null) onOpenDir(item.file)
                                else onToggleItem(item)
                            }
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(iconFor(item), contentDescription = null, tint = if (item.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    val sub = when {
                                        item.isProtected -> "Dossier protégé par Android"
                                        item.isDirectory -> "${item.childCount} élément(s)"
                                        else -> formatSize(item.size)
                                    }
                                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!item.isProtected) {
                                    Checkbox(checked = checked, onCheckedChange = { onToggleItem(item) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun iconFor(item: ExplorerItem) = when {
    item.isProtected -> Icons.Default.Lock
    item.isDirectory -> Icons.Default.Folder
    item.extension in setOf("mp4", "mkv", "avi", "mov", "webm", "3gp") -> Icons.Default.VideoFile
    item.extension in setOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "opus") -> Icons.Default.AudioFile
    item.extension in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "raw", "dng") -> Icons.Default.Image
    item.extension in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "apk", "xapk") -> Icons.Default.FolderZip
    item.extension in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "epub") -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}
