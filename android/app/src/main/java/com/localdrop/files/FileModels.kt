package com.localdrop.files

import android.net.Uri
import com.localdrop.protocol.FileEntry
import java.io.File

enum class FileCategory(val label: String) {
    MEMORY("Mémoire"),
    VIDEOS("Vidéos"),
    AUDIO("Audio"),
    PHOTOS("Photos"),
    DOCUMENTS("Documents"),
    ARCHIVES("Archives & Applis"),
}

enum class SortField(val label: String) {
    NAME("Nom"),
    DATE("Date"),
    SIZE("Taille"),
}

data class StorageVolumeInfo(
    val id: String,
    val label: String,
    val root: File,
    val freeBytes: Long,
    val totalBytes: Long,
    val isRemovable: Boolean,
)

data class ExplorerItem(
    val key: String,              // identifiant unique stable dans le panier
    val name: String,
    val uri: Uri,
    val file: File?,              // non null pour les fichiers/dossiers réels du navigateur
    val isDirectory: Boolean,
    val size: Long,               // pour un dossier : 0 ou taille calculée si connue
    val childCount: Int,          // nombre d'éléments directs si dossier
    val lastModifiedMs: Long,
    val extension: String,
    val isProtected: Boolean = false,
)

data class DirectoryListing(
    val directory: File,
    val items: List<ExplorerItem>,
    val isProtected: Boolean,
)

data class PickedItem(
    val key: String,
    val name: String,
    val uri: Uri,
    val file: File?,
    val isDirectory: Boolean,
    val approximateSize: Long,
)

data class PreparedBatch(
    val entries: List<FileEntry>,
    val sources: Map<Int, Uri>,
    val totalBytes: Long,
)

fun formatSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes o"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.0f Ko".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f Mo".format(mb)
    val gb = mb / 1024.0
    return "%.2f Go".format(gb)
}
