package com.localdrop.session

import android.content.Context
import android.net.Uri
import com.localdrop.files.ExplorerItem
import com.localdrop.files.FileCategory
import com.localdrop.files.PickedItem
import com.localdrop.files.PreparedBatch
import com.localdrop.files.SortField
import com.localdrop.files.StorageScanner
import com.localdrop.files.StorageVolumeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FilePickerDelegate(
    private val ctx: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PickerUiState())
    val state: StateFlow<PickerUiState> = _state.asStateFlow()

    fun refreshPermissionsAndVolumes() {
        scope.launch {
            val allFiles = StorageScanner.hasAllFilesAccess(ctx)
            val mediaOk = StorageScanner.hasMediaAccess(ctx)
            val canAllScreen = StorageScanner.canUseAllFilesScreen(ctx)
            val vols = withContext(Dispatchers.IO) { StorageScanner.storageVolumes(ctx) }
            val currentVol = _state.value.selectedVolume?.takeIf { v -> vols.any { it.id == v.id } } ?: vols.firstOrNull()
            val startDir = _state.value.currentDir?.takeIf { it.exists() } ?: currentVol?.root
            val nextCategory = if (!allFiles && mediaOk && _state.value.category == FileCategory.MEMORY) {
                FileCategory.VIDEOS
            } else {
                _state.value.category
            }
            _state.update {
                it.copy(
                    hasAllFilesAccess = allFiles,
                    hasMediaAccess = mediaOk,
                    canUseAllFilesScreen = canAllScreen,
                    category = nextCategory,
                    volumes = vols,
                    selectedVolume = currentVol,
                    currentDir = startDir
                )
            }
            reloadContent()
        }
    }

    fun selectCategory(cat: FileCategory) {
        _state.update { it.copy(category = cat, query = "") }
        reloadContent()
    }

    fun selectVolume(vol: StorageVolumeInfo) {
        _state.update { it.copy(category = FileCategory.MEMORY, selectedVolume = vol, currentDir = vol.root, query = "") }
        reloadContent()
    }

    fun openDirectory(dir: File) {
        _state.update { it.copy(category = FileCategory.MEMORY, currentDir = dir, query = "") }
        reloadContent()
    }

    fun navigateUp(): Boolean {
        val st = _state.value
        if (st.category != FileCategory.MEMORY) return false
        val cur = st.currentDir ?: return false
        val root = st.selectedVolume?.root
        if (root != null && cur.canonicalPath == root.canonicalPath) return false
        val parent = cur.parentFile ?: return false
        openDirectory(parent)
        return true
    }

    fun updateQuery(q: String) {
        _state.update { it.copy(query = q) }
        reloadContent()
    }

    fun toggleSort(field: SortField) {
        _state.update {
            val asc = if (it.sortField == field) !it.ascending else (field == SortField.NAME)
            it.copy(sortField = field, ascending = asc)
        }
        reloadContent()
    }

    fun reloadContent() {
        val st = _state.value
        scope.launch {
            _state.update { it.copy(loading = true) }
            if (st.category == FileCategory.MEMORY) {
                val dir = st.currentDir
                if (dir == null) {
                    _state.update { it.copy(loading = false, items = emptyList()) }
                    return@launch
                }
                val listing = StorageScanner.listDirectory(dir, st.query, st.sortField, st.ascending)
                _state.update { it.copy(loading = false, items = listing.items, isProtectedDir = listing.isProtected) }
            } else {
                val list = StorageScanner.loadCategory(ctx, st.category, st.query, st.sortField, st.ascending)
                _state.update { it.copy(loading = false, items = list, isProtectedDir = false) }
            }
        }
    }

    fun toggleSelection(item: ExplorerItem) {
        if (item.isProtected) return
        _state.update { st ->
            val next = st.cart.toMutableMap()
            if (next.containsKey(item.key)) next.remove(item.key)
            else next[item.key] = PickedItem(item.key, item.name, item.uri, item.file, item.isDirectory, item.size)
            st.copy(cart = next)
        }
    }

    fun addSafUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val picked = StorageScanner.fromSafUris(ctx, uris)
        _state.update { st ->
            val next = st.cart.toMutableMap()
            picked.forEach { next[it.key] = it }
            st.copy(cart = next)
        }
    }

    fun addSafTree(treeUri: Uri) {
        val name = treeUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "Dossier"
        val picked = PickedItem("saftree:$treeUri", name, treeUri, null, isDirectory = true, approximateSize = 0L)
        _state.update { st -> st.copy(cart = st.cart + (picked.key to picked)) }
    }

    fun clearCart() {
        _state.update { it.copy(cart = emptyMap()) }
    }

    suspend fun expandCart(): PreparedBatch {
        val selected = _state.value.cart.values.toList()
        _state.update { it.copy(loading = true) }
        return try {
            StorageScanner.expandSelectionToBatch(ctx, selected)
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }
}
