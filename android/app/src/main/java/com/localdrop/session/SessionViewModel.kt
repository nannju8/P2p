package com.localdrop.session

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localdrop.crypto.SecureChannel
import com.localdrop.crypto.SessionKeys
import com.localdrop.data.AppPreferences
import com.localdrop.data.AppSettings
import com.localdrop.data.TransferRecord
import com.localdrop.files.ExplorerItem
import com.localdrop.files.FileCategory
import com.localdrop.files.PreparedBatch
import com.localdrop.files.SortField
import com.localdrop.files.StorageVolumeInfo
import com.localdrop.network.DiscoveryManager
import com.localdrop.network.HotspotController
import com.localdrop.network.JoinResult
import com.localdrop.network.NetworkBinder
import com.localdrop.network.PairResult
import com.localdrop.network.Peer
import com.localdrop.network.Progress
import com.localdrop.network.ReceiverPairing
import com.localdrop.network.SenderPairing
import com.localdrop.network.TransferEngine
import com.localdrop.network.TransferService
import com.localdrop.network.TransferStore
import com.localdrop.protocol.FileReceiver
import com.localdrop.protocol.FrameType
import com.localdrop.protocol.Manifest
import com.localdrop.protocol.PairingGuard
import com.localdrop.ui.QrHelper
import com.localdrop.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SessionViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication()
    val prefs = AppPreferences(ctx)

    val settings: StateFlow<AppSettings> = prefs.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings("ld", android.os.Build.MODEL, "", ThemeMode.Dark)
    )
    val history: StateFlow<List<TransferRecord>> = prefs.history.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    private val binder = NetworkBinder(ctx)
    private val hotspot = HotspotController(ctx)
    private val discovery = DiscoveryManager(ctx, binder, viewModelScope)

    private val pickerDelegate = FilePickerDelegate(ctx, viewModelScope)
    private val webShareDelegate = WebShareDelegate(ctx, viewModelScope, binder, hotspot) {
        _network.update { it.copy(hotspotInfo = null) }
    }

    val discoveredPeers: StateFlow<List<Peer>> = discovery.peers
    val picker: StateFlow<PickerUiState> = pickerDelegate.state
    val webShare: StateFlow<WebShareUiState> = webShareDelegate.state

    private val _screen = MutableStateFlow(AppScreen.Home)
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    private val _network = MutableStateFlow(NetworkBannerState())
    val network: StateFlow<NetworkBannerState> = _network.asStateFlow()

    private var pairingGuard: PairingGuard? = null
    private val _senderCode = MutableStateFlow("0000")
    val senderCode: StateFlow<String> = _senderCode.asStateFlow()
    private val _senderCodeRemainingMs = MutableStateFlow(0L)
    val senderCodeRemainingMs: StateFlow<Long> = _senderCodeRemainingMs.asStateFlow()
    private val _pairingFeedback = MutableStateFlow<String?>(null)
    val pairingFeedback: StateFlow<String?> = _pairingFeedback.asStateFlow()

    private val _transfer = MutableStateFlow(TransferSessionUi())
    val transfer: StateFlow<TransferSessionUi> = _transfer.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var senderPairingHelper: SenderPairing? = null
    private var activeControl: SecureChannel? = null
    private var activeKeys: SessionKeys? = null
    private var activePeerName: String = "Appareil voisin"
    private var pendingBatch: PreparedBatch? = null
    private var sessionJob: Job? = null

    val hasActivePairedSession: Boolean get() = activeControl != null && activeKeys != null

    init {
        viewModelScope.launch {
            while (isActive) {
                refreshNetworkBanner()
                delay(1_500)
            }
        }
    }

    fun navigateTo(target: AppScreen) { _screen.value = target }
    fun openSendFlow() { _pairingFeedback.value = null; pickerDelegate.refreshPermissionsAndVolumes(); _screen.value = AppScreen.FilePicker }
    fun openReceiveFlow() { _pairingFeedback.value = null; _screen.value = AppScreen.ReceiverPairing; startReceiverDiscovery() }
    fun openShareWebFlow() { _screen.value = AppScreen.ShareWeb; webShareDelegate.start() }

    @Suppress("DEPRECATION")
    fun refreshNetworkBanner() {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiNet = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = runCatching {
            wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        }.getOrNull().orEmpty()
        val ip = binder.localIpv4()?.hostAddress.orEmpty()

        _network.update {
            it.copy(
                wifiConnected = wifiNet != null || binder.wifi != null || it.hotspotInfo != null || it.wifiDirectActive,
                wifiName = ssid.ifBlank { it.hotspotInfo?.ssid ?: if (it.wifiDirectActive) "Wi-Fi Direct 5 GHz" else "" },
                localIp = ip,
            )
        }
        webShareDelegate.updateIpIfRunning(ip)
    }

    fun connectViaWifiQr(rawQr: String) {
        val parsed = QrHelper.parseWifiQr(rawQr)
        if (parsed == null) {
            _network.update { it.copy(error = "QR Code non reconnu (utilisez le QR Wi-Fi affiché sur l'envoyeur).") }
            return
        }
        _network.update { it.copy(connectingWifi = true, error = null) }
        binder.connectToWifi(parsed.ssid, parsed.password) { ok, err ->
            _network.update { it.copy(connectingWifi = false, error = if (ok) null else err) }
            if (ok) {
                viewModelScope.launch {
                    delay(400)
                    refreshNetworkBanner()
                    discovery.startDiscovery(settings.value.deviceId)
                }
            }
        }
    }

    fun toggleLocalHotspot() {
        if (_network.value.hotspotInfo != null) {
            hotspot.stopHotspot()
            _network.update { it.copy(hotspotInfo = null, error = null) }
            refreshNetworkBanner()
            return
        }
        hotspot.startLocalOnlyHotspot(
            onReady = { info ->
                _network.update { it.copy(hotspotInfo = info, error = null) }
                viewModelScope.launch {
                    repeat(6) { delay(300); refreshNetworkBanner(); if (_network.value.localIp.isNotBlank()) return@launch }
                }
            },
            onError = { msg -> _network.update { it.copy(error = msg) } }
        )
    }

    fun startWifiDirect5Ghz() {
        hotspot.createWifiDirectGroup { ok ->
            _network.update { it.copy(wifiDirectActive = ok, error = if (ok) null else "Wi-Fi Direct indisponible.") }
            refreshNetworkBanner()
        }
    }

    fun clearNetworkError() { _network.update { it.copy(error = null) } }

    fun refreshPickerPermissionsAndVolumes() = pickerDelegate.refreshPermissionsAndVolumes()
    fun selectCategory(cat: FileCategory) = pickerDelegate.selectCategory(cat)
    fun selectStorageVolume(vol: StorageVolumeInfo) = pickerDelegate.selectVolume(vol)
    fun openDirectory(dir: File) = pickerDelegate.openDirectory(dir)
    fun navigateUpDirectory(): Boolean = pickerDelegate.navigateUp()
    fun updateSearchQuery(q: String) = pickerDelegate.updateQuery(q)
    fun toggleSort(field: SortField) = pickerDelegate.toggleSort(field)
    fun toggleItemSelection(item: ExplorerItem) = pickerDelegate.toggleSelection(item)
    fun addSafSelection(uris: List<Uri>) = pickerDelegate.addSafUris(uris)
    fun addSafTree(treeUri: Uri) = pickerDelegate.addSafTree(treeUri)
    fun clearCart() = pickerDelegate.clearCart()

    fun startWebShare() = webShareDelegate.start()
    fun toggleWebSharePin(enabled: Boolean) = webShareDelegate.togglePin(enabled)
    fun stopWebShare() = webShareDelegate.stop()

    fun confirmCartAndProceed() {
        if (pickerDelegate.state.value.cart.isEmpty()) return
        viewModelScope.launch {
            val batch = pickerDelegate.expandCart()
            if (batch.entries.isEmpty()) {
                _pairingFeedback.value = "Aucun fichier lisible trouvé dans la sélection."
                return@launch
            }
            pendingBatch = batch
            val ctrl = activeControl; val keys = activeKeys; val pairing = senderPairingHelper
            if (ctrl != null && keys != null && pairing != null) launchSenderTransfer(batch, ctrl, pairing, keys)
            else startSenderPairingWait()
        }
    }

    private fun startSenderPairingWait() {
        sessionJob?.cancel()
        _pairingFeedback.value = null
        _screen.value = AppScreen.SenderPairing
        val guard = PairingGuard().also { pairingGuard = it }
        _senderCode.value = guard.displayedCode.value

        sessionJob = viewModelScope.launch {
            launch { guard.displayedCode.collect { _senderCode.value = it } }
            launch {
                while (isActive) {
                    val rem = guard.remainingMs(); _senderCodeRemainingMs.value = rem
                    if (rem == 0L) guard.rotate(); delay(500)
                }
            }
            withContext(Dispatchers.IO) {
                binder.acquireWifi(1_500); binder.bindProcess(true)
                val srv = binder.listen(0).also { serverSocket = it }
                val mode = when {
                    _network.value.hotspotInfo != null -> "Point d'accès"
                    _network.value.wifiDirectActive -> "Wi-Fi Direct 5GHz"
                    else -> "Wi-Fi"
                }
                val s = settings.first()
                discovery.advertise(s.deviceId, s.deviceName, srv.localPort, mode)
                val pairing = SenderPairing(srv, guard, binder).also { senderPairingHelper = it }
                try {
                    val res = pairing.awaitPairing { evt ->
                        _pairingFeedback.value = when (evt) {
                            is PairResult.BadCode -> "Code incorrect (${evt.remaining} essai(s) restant(s))."
                            is PairResult.Locked -> "Bloqué ${evt.cooldownMs / 1000}s : nouveau code généré."
                            PairResult.Expired -> "Code expiré : nouveau code généré."
                            is PairResult.Success -> null
                        }
                    }
                    discovery.stop()
                    activeControl = res.control; activeKeys = res.keys
                    pendingBatch?.let { launchSenderTransfer(it, res.control, pairing, res.keys) }
                } catch (_: CancellationException) {
                } catch (e: Exception) {
                    if (isActive) _pairingFeedback.value = e.message ?: "Erreur réseau"
                }
            }
        }
    }

    fun rotateSenderCode() { pairingGuard?.rotate() }

    private fun launchSenderTransfer(batch: PreparedBatch, control: SecureChannel, pairing: SenderPairing, keys: SessionKeys) {
        _screen.value = AppScreen.Transferring
        sessionJob?.cancel()
        _transfer.value = TransferSessionUi(
            isSender = true, peerName = activePeerName, running = true,
            totalBytes = batch.totalBytes, files = batch.entries.map { TransferFileUi(it.id, it.relPath, it.size) }
        )
        TransferService.start(ctx); TransferStore.begin(batch.totalBytes)
        val doneTotal = AtomicLong(0L)
        val perFile = ConcurrentHashMap<Int, AtomicLong>().apply { batch.entries.forEach { put(it.id, AtomicLong(0L)) } }

        sessionJob = viewModelScope.launch {
            TransferStore.cancelHook = { cancelActiveTransfer() }
            val ticker = launchSpeedTicker(this, _transfer, doneTotal, perFile, batch.totalBytes)
            val startMs = System.currentTimeMillis()
            var ok = false; var cancelled = false; var err: String? = null
            try {
                TransferEngine.sendBatch(control, pairing, keys, batch.entries, batch.sources, ctx.contentResolver,
                    Progress { id, d -> doneTotal.addAndGet(d); perFile[id]?.addAndGet(d) })
                ok = true
            } catch (_: CancellationException) { cancelled = true }
            catch (e: Exception) { err = e.message ?: "Erreur d'envoi" }
            finally { ticker.cancel(); TransferStore.finish(cancelled) }

            val avg = (doneTotal.get() / ((System.currentTimeMillis() - startMs) / 1000.0).coerceAtLeast(0.2)).toLong()
            _transfer.update { st ->
                st.copy(
                    running = false, completed = ok, cancelled = cancelled, errorMessage = err,
                    doneBytes = if (ok) st.totalBytes else doneTotal.get(), speedBps = avg, etaSeconds = 0,
                    files = st.files.map { f ->
                        if (ok) f.copy(doneBytes = f.totalBytes, status = FileItemStatus.Done)
                        else f.copy(status = if (f.doneBytes >= f.totalBytes) FileItemStatus.Done else FileItemStatus.Error)
                    }
                )
            }
            pickerDelegate.clearCart()
            prefs.addHistory(TransferRecord(UUID.randomUUID().toString(), true, activePeerName, batch.entries.size, batch.totalBytes, avg, System.currentTimeMillis(), ok))
            if (!ok) endSession(keepScreen = true)
        }
    }

    fun startReceiverDiscovery() {
        viewModelScope.launch {
            binder.acquireWifi(2_000); binder.bindProcess(true)
            discovery.startDiscovery(settings.first().deviceId)
        }
    }

    fun joinSenderWithCode(peer: Peer, typedCode: String) {
        if (typedCode.length != 4) return
        _pairingFeedback.value = "Vérification cryptographique..."
        activePeerName = peer.name
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    binder.acquireWifi(2_000); binder.bindProcess(true)
                    ReceiverPairing(binder).join(peer, typedCode.toCharArray())
                }
                when (res) {
                    is JoinResult.Success -> {
                        _pairingFeedback.value = null; discovery.stop()
                        activeControl = res.control; activeKeys = res.keys
                        startReceiverBatchLoop(peer, res.control, res.keys)
                    }
                    is JoinResult.BadCode -> _pairingFeedback.value = "Code incorrect (${res.remaining} essai(s) restant(s))."
                    is JoinResult.Locked -> _pairingFeedback.value = "Bloqué ${res.cooldownMs / 1000}s. Attendez le nouveau code."
                    JoinResult.Expired -> _pairingFeedback.value = "Code expiré. Demandez un nouveau code."
                }
            } catch (e: Exception) {
                _pairingFeedback.value = "Connexion impossible : ${e.message ?: "vérifiez le Wi-Fi"}"
            }
        }
    }

    private fun startReceiverBatchLoop(peer: Peer, control: SecureChannel, keys: SessionKeys) {
        _screen.value = AppScreen.Transferring
        sessionJob?.cancel()
        _transfer.value = TransferSessionUi(isSender = false, peerName = peer.name, running = true, waitingNextBatch = true)

        sessionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val frame = control.receive()
                    when (frame.type) {
                        FrameType.MANIFEST -> {
                            val manifest = Manifest.decode(frame.payload)
                            val prefDest = settings.first().destinationPath.takeIf { it.isNotBlank() }?.let(::File)
                            val destDir = FileReceiver.resolveDestinationDir(ctx, prefDest)
                            val doneTotal = AtomicLong(0L)
                            val perFile = ConcurrentHashMap<Int, AtomicLong>().apply { manifest.entries.forEach { put(it.id, AtomicLong(0L)) } }

                            _transfer.value = TransferSessionUi(
                                isSender = false, peerName = peer.name, running = true, waitingNextBatch = false,
                                totalBytes = manifest.totalBytes, savedDirPath = destDir.path,
                                files = manifest.entries.map { TransferFileUi(it.id, it.relPath, it.size) }
                            )
                            TransferService.start(ctx); TransferStore.begin(manifest.totalBytes)
                            TransferStore.cancelHook = { cancelActiveTransfer() }

                            val ticker = launchSpeedTicker(this, _transfer, doneTotal, perFile, manifest.totalBytes)
                            val startMs = System.currentTimeMillis()
                            var ok = false; var cancelled = false; var err: String? = null
                            try {
                                TransferEngine.receiveBatch(control, frame, { m -> FileReceiver(destDir, m, ctx) },
                                    { ReceiverPairing(binder).openWorker(peer, keys) },
                                    Progress { id, d -> doneTotal.addAndGet(d); perFile[id]?.addAndGet(d) })
                                ok = true
                            } catch (_: CancellationException) { cancelled = true }
                            catch (e: Exception) { err = e.message ?: "Erreur de réception" }
                            finally { ticker.cancel(); TransferStore.finish(cancelled) }

                            val avg = (doneTotal.get() / ((System.currentTimeMillis() - startMs) / 1000.0).coerceAtLeast(0.2)).toLong()
                            _transfer.update { st ->
                                st.copy(
                                    running = false, completed = ok, waitingNextBatch = ok, cancelled = cancelled, errorMessage = err,
                                    doneBytes = if (ok) st.totalBytes else doneTotal.get(), speedBps = avg, etaSeconds = 0,
                                    files = st.files.map { f ->
                                        if (ok) f.copy(doneBytes = f.totalBytes, status = FileItemStatus.Done)
                                        else f.copy(status = if (f.doneBytes >= f.totalBytes) FileItemStatus.Done else FileItemStatus.Error)
                                    }
                                )
                            }
                            prefs.addHistory(TransferRecord(UUID.randomUUID().toString(), false, peer.name, manifest.entries.size, manifest.totalBytes, avg, System.currentTimeMillis(), ok, destDir.path))
                            if (!ok) break
                        }
                        FrameType.SESSION_END -> break
                        FrameType.CANCEL -> { _transfer.update { it.copy(running = false, cancelled = true) }; break }
                        else -> break
                    }
                }
            } catch (_: Exception) {
            } finally {
                endSession(keepScreen = true)
            }
        }
    }

    fun cancelActiveTransfer() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { activeControl?.send(FrameType.CANCEL) }; endSession(keepScreen = true) }
        _transfer.update { it.copy(running = false, cancelled = true) }
        TransferStore.finish(cancelled = true)
    }

    fun endSession(keepScreen: Boolean = false) {
        val ctrl = activeControl; val keys = activeKeys; val srv = serverSocket
        activeControl = null; activeKeys = null; serverSocket = null; senderPairingHelper = null; pairingGuard = null; pendingBatch = null
        sessionJob?.cancel(); sessionJob = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ctrl?.send(FrameType.SESSION_END) }
            runCatching { ctrl?.close() }; runCatching { srv?.close() }; runCatching { keys?.destroy() }
        }
        discovery.stop(); binder.release()
        if (!keepScreen) {
            hotspot.stopHotspot(); hotspot.removeWifiDirectGroup()
            _network.update { it.copy(hotspotInfo = null, wifiDirectActive = false, connectingWifi = false) }
            _screen.value = AppScreen.Home
        }
    }

    fun updateDeviceName(name: String) { viewModelScope.launch { prefs.setDeviceName(name) } }
    fun updateDestinationPath(path: String) { viewModelScope.launch { prefs.setDestinationPath(path) } }
    fun updateThemeMode(mode: ThemeMode) { viewModelScope.launch { prefs.setThemeMode(mode) } }
    fun clearHistory() { viewModelScope.launch { prefs.clearHistory() } }

    override fun onCleared() { stopWebShare(); endSession(keepScreen = false); super.onCleared() }
}
