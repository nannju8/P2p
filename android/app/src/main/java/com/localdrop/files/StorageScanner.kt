package com.localdrop.files

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.localdrop.crypto.PathSanitizer
import com.localdrop.protocol.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object StorageScanner {

    private val DOC_EXTS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "txt", "csv", "epub", "odt", "ods", "odp", "rtf", "md", "json", "xml"
    )
    private val ARCHIVE_EXTS = setOf(
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso", "apk", "xapk", "apks"
    )

    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /** Détecte le stockage interne + toute carte SD amovible montée. */
    fun storageVolumes(context: Context): List<StorageVolumeInfo> {
        val result = mutableListOf<StorageVolumeInfo>()
        val seenPaths = HashSet<String>()

        if (Build.VERSION.SDK_INT >= 30) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            sm.storageVolumes.forEach { vol ->
                val dir = vol.directory ?: return@forEach
                val canon = runCatching { dir.canonicalPath }.getOrDefault(dir.path)
                if (dir.exists() && seenPaths.add(canon)) {
                    val stat = runCatching { StatFs(dir.path) }.getOrNull()
                    result += StorageVolumeInfo(
                        id = canon,
                        label = if (vol.isRemovable) vol.getDescription(context).ifBlank { "Carte SD" } else "Stockage interne",
                        root = dir,
                        freeBytes = stat?.availableBytes ?: 0L,
                        totalBytes = stat?.totalBytes ?: 0L,
                        isRemovable = vol.isRemovable,
                    )
                }
            }
        }

        if (result.isEmpty()) {
            val primary = Environment.getExternalStorageDirectory()
            val primaryCanon = runCatching { primary.canonicalPath }.getOrDefault(primary.path)
            if (primary.exists() && seenPaths.add(primaryCanon)) {
                val stat = runCatching { StatFs(primary.path) }.getOrNull()
                result += StorageVolumeInfo(
                    id = primaryCanon,
                    label = "Stockage interne",
                    root = primary,
                    freeBytes = stat?.availableBytes ?: 0L,
                    totalBytes = stat?.totalBytes ?: 0L,
                    isRemovable = false,
                )
            }
            context.getExternalFilesDirs(null).forEach { appDir ->
                val path = appDir?.path ?: return@forEach
                val idx = path.indexOf("/Android/")
                if (idx > 0) {
                    val root = File(path.substring(0, idx))
                    val canon = runCatching { root.canonicalPath }.getOrDefault(root.path)
                    if (root.exists() && seenPaths.add(canon)) {
                        val stat = runCatching { StatFs(root.path) }.getOrNull()
                        result += StorageVolumeInfo(
                            id = canon,
                            label = "Carte SD",
                            root = root,
                            freeBytes = stat?.availableBytes ?: 0L,
                            totalBytes = stat?.totalBytes ?: 0L,
                            isRemovable = true,
                        )
                    }
                }
            }
        }
        return result
    }

    /** Navigation directe dans la mémoire (gère proprement Android/data et Android/obb sans crash). */
    suspend fun listDirectory(
        dir: File,
        query: String = "",
        sort: SortField = SortField.NAME,
        ascending: Boolean = true,
    ): DirectoryListing = withContext(Dispatchers.IO) {
        val raw = runCatching { dir.listFiles() }.getOrNull()
        if (raw == null) {
            return@withContext DirectoryListing(dir, emptyList(), isProtected = true)
        }
        val q = query.trim().lowercase(Locale.ROOT)
        val filtered = raw.filter { f ->
            !f.name.startsWith(".") && (q.isEmpty() || f.name.lowercase(Locale.ROOT).contains(q))
        }
        val mapped = filtered.map { f ->
            val isDir = f.isDirectory
            val children = if (isDir) runCatching { f.listFiles()?.count { !it.name.startsWith(".") } ?: -1 }.getOrDefault(-1) else 0
            val protectedDir = isDir && children < 0
            ExplorerItem(
                key = "file:${f.path}",
                name = f.name,
                uri = Uri.fromFile(f),
                file = f,
                isDirectory = isDir,
                size = if (isDir) 0L else f.length(),
                childCount = children.coerceAtLeast(0),
                lastModifiedMs = f.lastModified(),
                extension = if (isDir) "" else f.extension.lowercase(Locale.ROOT),
                isProtected = protectedDir,
            )
        }
        DirectoryListing(dir, sortItems(mapped, sort, ascending), isProtected = false)
    }

    /** Chargement par catégorie (Vidéos, Audio, Photos, Documents, Archives & Applis). */
    suspend fun loadCategory(
        context: Context,
        category: FileCategory,
        query: String = "",
        sort: SortField = SortField.DATE,
        ascending: Boolean = false,
    ): List<ExplorerItem> = withContext(Dispatchers.IO) {
        val items = when (category) {
            FileCategory.MEMORY -> emptyList()
            FileCategory.VIDEOS -> queryMediaStore(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            FileCategory.AUDIO -> queryMediaStore(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            FileCategory.PHOTOS -> queryMediaStore(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            FileCategory.DOCUMENTS -> scanExtensions(context, DOC_EXTS)
            FileCategory.ARCHIVES -> scanExtensions(context, ARCHIVE_EXTS)
        }
        val q = query.trim().lowercase(Locale.ROOT)
        val filtered = if (q.isEmpty()) items else items.filter { it.name.lowercase(Locale.ROOT).contains(q) }
        sortItems(filtered, sort, ascending)
    }

    private fun queryMediaStore(context: Context, collection: Uri): List<ExplorerItem> {
        val result = mutableListOf<ExplorerItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA,
        )
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val path = if (dataCol >= 0) c.getString(dataCol) else null
                    val fileObj = path?.let(::File)?.takeIf { it.exists() && it.canRead() }
                    val name = (if (nameCol >= 0) c.getString(nameCol) else null) ?: fileObj?.name ?: "fichier_$id"
                    val size = (if (sizeCol >= 0) c.getLong(sizeCol) else 0L).let { if (it > 0) it else fileObj?.length() ?: 0L }
                    if (size <= 0L) continue
                    val mod = (if (dateCol >= 0) c.getLong(dateCol) * 1000L else 0L).let { if (it > 0) it else fileObj?.lastModified() ?: 0L }
                    val uri = fileObj?.let(Uri::fromFile) ?: ContentUris.withAppendedId(collection, id)
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    result += ExplorerItem(
                        key = "media:$collection:$id",
                        name = name,
                        uri = uri,
                        file = fileObj,
                        isDirectory = false,
                        size = size,
                        childCount = 0,
                        lastModifiedMs = mod,
                        extension = ext,
                    )
                }
            }
        }
        return result
    }

    /**
     * Pour Documents et Archives (.rar, .7z, .zip, .apk, .pdf...) :
     * si l'accès complet est accordé, scanne directement la mémoire pour trouver même les fichiers
     * non encore indexés par MediaStore ; sinon repli sur MediaStore.Files.
     */
    private fun scanExtensions(context: Context, extensions: Set<String>): List<ExplorerItem> {
        if (hasAllFilesAccess(context)) {
            val found = mutableListOf<ExplorerItem>()
            val seen = HashSet<String>()
            storageVolumes(context).forEach { vol ->
                vol.root.walkTopDown()
                    .onEnter { dir ->
                        if (dir.name.startsWith(".")) return@onEnter false
                        val p = dir.path
                        // Évite les dossiers systèmes Android/data et Android/obb
                        if (p.endsWith("/Android/data") || p.endsWith("/Android/obb")) return@onEnter false
                        dir.canRead()
                    }
                    .forEach { f ->
                        if (f.isFile && !f.name.startsWith(".")) {
                            val ext = f.extension.lowercase(Locale.ROOT)
                            if (ext in extensions && f.length() > 0 && seen.add(f.path)) {
                                found += ExplorerItem(
                                    key = "file:${f.path}",
                                    name = f.name,
                                    uri = Uri.fromFile(f),
                                    file = f,
                                    isDirectory = false,
                                    size = f.length(),
                                    childCount = 0,
                                    lastModifiedMs = f.lastModified(),
                                    extension = ext,
                                )
                            }
                        }
                    }
            }
            return found
        }

        // Repli MediaStore.Files si MANAGE_EXTERNAL_STORAGE refusé
        val all = queryMediaStore(context, MediaStore.Files.getContentUri("external"))
        return all.filter { it.extension in extensions }
    }

    private fun sortItems(items: List<ExplorerItem>, sort: SortField, ascending: Boolean): List<ExplorerItem> {
        val base = Comparator<ExplorerItem> { a, b ->
            if (a.isDirectory != b.isDirectory) return@Comparator if (a.isDirectory) -1 else 1
            val cmp = when (sort) {
                SortField.NAME -> a.name.lowercase(Locale.ROOT).compareTo(b.name.lowercase(Locale.ROOT))
                SortField.DATE -> a.lastModifiedMs.compareTo(b.lastModifiedMs)
                SortField.SIZE -> a.size.compareTo(b.size)
            }
            if (ascending) cmp else -cmp
        }
        return items.sortedWith(base)
    }

    /** Convertit des URI choisis via le sélecteur système SAF (`ACTION_OPEN_DOCUMENT`) en PickedItem. */
    fun fromSafUris(context: Context, uris: List<Uri>): List<PickedItem> {
        val resolver = context.contentResolver
        return uris.mapNotNull { uri ->
            var name = "fichier"
            var size = 0L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val nc = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sc = c.getColumnIndex(OpenableColumns.SIZE)
                        if (nc >= 0) name = c.getString(nc) ?: name
                        if (sc >= 0) size = c.getLong(sc)
                    }
                }
            }
            PickedItem(
                key = "saf:$uri",
                name = name,
                uri = uri,
                file = null,
                isDirectory = false,
                approximateSize = size,
            )
        }
    }

    /**
     * Transforme le panier utilisateur (fichiers + dossiers entiers) en lot prêt pour TransferEngine.sendBatch().
     * Préserve l'arborescence des dossiers et garantit des `relPath` uniques et sûrs.
     */
    suspend fun expandSelectionToBatch(context: Context, selected: List<PickedItem>): PreparedBatch =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val entries = mutableListOf<FileEntry>()
            val sources = LinkedHashMap<Int, Uri>()
            val usedPaths = HashSet<String>()
            var nextId = 1
            var totalBytes = 0L

            fun addEntry(rawRelPath: String, uri: Uri, size: Long) {
                if (size < 0) return
                val clean = PathSanitizer.sanitizeRelativePath(rawRelPath)
                val unique = ensureUniqueRelPath(clean, usedPaths)
                val id = nextId++
                entries += FileEntry(id, unique, size)
                sources[id] = uri
                totalBytes += size
            }

            for (item in selected) {
                val f = item.file
                if (item.isDirectory && f != null && f.isDirectory) {
                    val rootName = f.name.ifBlank { "Dossier" }
                    f.walkTopDown()
                        .onEnter { sub -> !sub.name.startsWith(".") && sub.canRead() }
                        .forEach { child ->
                            if (child.isFile && !child.name.startsWith(".")) {
                                val relFromParent = child.relativeTo(f).path.replace(File.separatorChar, '/')
                                addEntry("$rootName/$relFromParent", Uri.fromFile(child), child.length())
                            }
                        }
                } else if (item.isDirectory && item.uri.scheme == "content") {
                    collectSafTree(resolver, item.uri, item.name.ifBlank { "Dossier" }, ::addEntry)
                } else if (f != null && f.isFile) {
                    addEntry(f.name, Uri.fromFile(f), f.length())
                } else {
                    val realSize = if (item.approximateSize > 0) item.approximateSize else queryUriSize(resolver, item.uri)
                    addEntry(item.name, item.uri, realSize)
                }
            }
            PreparedBatch(entries, sources, totalBytes)
        }

    private fun collectSafTree(
        resolver: ContentResolver,
        treeUri: Uri,
        prefix: String,
        onFile: (String, Uri, Long) -> Unit,
    ) {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(docId to prefix)
        while (queue.isNotEmpty()) {
            val (parentDocId, curPrefix) = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            runCatching {
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: "element"
                        val mime = c.getString(2)
                        val size = c.getLong(3)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue.add(id to "$curPrefix/$name")
                        } else {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            onFile("$curPrefix/$name", fileUri, size.coerceAtLeast(0L))
                        }
                    }
                }
            }
        }
    }

    private fun queryUriSize(resolver: ContentResolver, uri: Uri): Long {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getLong(0).coerceAtLeast(0L)
            }
        }
        return runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize.coerceAtLeast(0L) } ?: 0L
        }.getOrDefault(0L)
    }

    private fun ensureUniqueRelPath(path: String, used: MutableSet<String>): String {
        if (used.add(path)) return path
        val slash = path.lastIndexOf('/')
        val dir = if (slash >= 0) path.substring(0, slash + 1) else ""
        val file = if (slash >= 0) path.substring(slash + 1) else path
        val dot = file.lastIndexOf('.').takeIf { it > 0 } ?: file.length
        val base = file.substring(0, dot)
        val ext = file.substring(dot)
        var i = 1
        while (true) {
            val candidate = "$dir$base ($i)$ext"
            if (used.add(candidate)) return candidate
            i++
        }
    }
}
