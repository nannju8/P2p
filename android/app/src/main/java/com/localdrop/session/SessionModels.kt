package com.localdrop.session

import com.localdrop.files.ExplorerItem
import com.localdrop.files.FileCategory
import com.localdrop.files.PickedItem
import com.localdrop.files.SortField
import com.localdrop.files.StorageVolumeInfo
import com.localdrop.network.HotspotInfo
import com.localdrop.network.TransferStore
import com.localdrop.web.ShareStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class AppScreen {
    Home,
    FilePicker,
    SenderPairing,
    ReceiverPairing,
    Transferring,
    ShareWeb,
    History,
    Settings,
}

data class NetworkBannerState(
    val wifiConnected: Boolean = false,
    val wifiName: String = "",
    val localIp: String = "",
    val hotspotInfo: HotspotInfo? = null,
    val wifiDirectActive: Boolean = false,
    val connectingWifi: Boolean = false,
    val error: String? = null,
)

data class PickerUiState(
    val category: FileCategory = FileCategory.MEMORY,
    val volumes: List<StorageVolumeInfo> = emptyList(),
    val selectedVolume: StorageVolumeInfo? = null,
    val currentDir: File? = null,
    val items: List<ExplorerItem> = emptyList(),
    val isProtectedDir: Boolean = false,
    val query: String = "",
    val sortField: SortField = SortField.NAME,
    val ascending: Boolean = true,
    val loading: Boolean = false,
    val hasAllFilesAccess: Boolean = false,
    val hasMediaAccess: Boolean = false,
    val canUseAllFilesScreen: Boolean = true,
    val cart: Map<String, PickedItem> = emptyMap(),
) {
    val cartCount: Int get() = cart.size
    val cartTotalBytes: Long get() = cart.values.sumOf { it.approximateSize }
}

enum class FileItemStatus { Waiting, Active, Done, Error }

data class TransferFileUi(
    val id: Int,
    val name: String,
    val totalBytes: Long,
    val doneBytes: Long = 0L,
    val status: FileItemStatus = FileItemStatus.Waiting,
)

data class TransferSessionUi(
    val isSender: Boolean = true,
    val peerName: String = "",
    val running: Boolean = false,
    val completed: Boolean = false,
    val waitingNextBatch: Boolean = false,
    val cancelled: Boolean = false,
    val errorMessage: String? = null,
    val totalBytes: Long = 0L,
    val doneBytes: Long = 0L,
    val speedBps: Long = 0L,
    val etaSeconds: Long = -1L,
    val files: List<TransferFileUi> = emptyList(),
    val savedDirPath: String = "",
)

data class WebShareUiState(
    val active: Boolean = false,
    val url: String = "",
    val pinEnabled: Boolean = false,
    val pinCode: String = "",
    val status: ShareStatus = ShareStatus(),
    val error: String? = null,
)

fun launchSpeedTicker(
    scope: CoroutineScope,
    transferFlow: MutableStateFlow<TransferSessionUi>,
    doneTotal: AtomicLong,
    perFile: ConcurrentHashMap<Int, AtomicLong>,
    totalBytes: Long,
): Job = scope.launch {
    var lastBytes = 0L
    var lastAt = System.currentTimeMillis()
    var emaSpeed = 0.0

    while (isActive) {
        delay(250)
        val now = System.currentTimeMillis()
        val cur = doneTotal.get()
        val dt = ((now - lastAt) / 1000.0).coerceAtLeast(0.05)
        val instant = (cur - lastBytes) / dt
        emaSpeed = if (emaSpeed == 0.0) instant else (0.35 * instant + 0.65 * emaSpeed)
        lastBytes = cur
        lastAt = now

        val speedLong = emaSpeed.toLong().coerceAtLeast(0L)
        TransferStore.update(cur, speedLong)

        transferFlow.update { st ->
            val eta = if (speedLong > 0 && totalBytes > cur) (totalBytes - cur) / speedLong else -1L
            st.copy(
                doneBytes = cur,
                speedBps = speedLong,
                etaSeconds = eta,
                files = st.files.map { f ->
                    val d = perFile[f.id]?.get() ?: f.doneBytes
                    val status = when {
                        d >= f.totalBytes && f.totalBytes > 0 -> FileItemStatus.Done
                        d > 0L -> FileItemStatus.Active
                        else -> FileItemStatus.Waiting
                    }
                    f.copy(doneBytes = d, status = status)
                }
            )
        }
    }
}
