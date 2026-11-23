package com.localdrop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localdrop.session.AppScreen
import com.localdrop.session.SessionViewModel
import com.localdrop.ui.FilePickerScreen
import com.localdrop.ui.HistoryScreen
import com.localdrop.ui.HomeScreen
import com.localdrop.ui.ReceiverPairingScreen
import com.localdrop.ui.SenderPairingScreen
import com.localdrop.ui.SettingsScreen
import com.localdrop.ui.ShareWebScreen
import com.localdrop.ui.TransferringScreen
import com.localdrop.ui.theme.LocalDropTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LocalDropApp() }
    }
}

@Composable
private fun LocalDropApp(vm: SessionViewModel = viewModel()) {
    val settings by vm.settings.collectAsState()
    val history by vm.history.collectAsState()
    val screen by vm.screen.collectAsState()
    val network by vm.network.collectAsState()
    val picker by vm.picker.collectAsState()
    val peers by vm.discoveredPeers.collectAsState()
    val senderCode by vm.senderCode.collectAsState()
    val senderRemMs by vm.senderCodeRemainingMs.collectAsState()
    val pairingFeedback by vm.pairingFeedback.collectAsState()
    val transfer by vm.transfer.collectAsState()
    val webShare by vm.webShare.collectAsState()

    BackHandler(enabled = screen != AppScreen.Home) {
        when (screen) {
            AppScreen.FilePicker -> if (!vm.navigateUpDirectory()) {
                if (vm.hasActivePairedSession) vm.navigateTo(AppScreen.Transferring)
                else vm.navigateTo(AppScreen.Home)
            }
            AppScreen.SenderPairing, AppScreen.ReceiverPairing -> vm.endSession(keepScreen = false)
            AppScreen.Transferring -> if (!transfer.running) vm.endSession(keepScreen = false)
            AppScreen.ShareWeb -> { vm.stopWebShare(); vm.navigateTo(AppScreen.Home) }
            AppScreen.History, AppScreen.Settings -> vm.navigateTo(AppScreen.Home)
            AppScreen.Home -> {}
        }
    }

    LocalDropTheme(themeMode = settings.themeMode) {
        when (screen) {
            AppScreen.Home -> HomeScreen(
                settings = settings,
                lastRecord = history.firstOrNull(),
                onSend = { vm.openSendFlow() },
                onReceive = { vm.openReceiveFlow() },
                onShareApp = { vm.openShareWebFlow() },
                onOpenHistory = { vm.navigateTo(AppScreen.History) },
                onOpenSettings = { vm.navigateTo(AppScreen.Settings) },
                onRenameDevice = { vm.updateDeviceName(it) }
            )
            AppScreen.FilePicker -> FilePickerScreen(
                state = picker,
                onRefresh = { vm.refreshPickerPermissionsAndVolumes() },
                onSelectCategory = { vm.selectCategory(it) },
                onSelectVolume = { vm.selectStorageVolume(it) },
                onOpenDir = { vm.openDirectory(it) },
                onNavigateUp = { vm.navigateUpDirectory() },
                onQueryChange = { vm.updateSearchQuery(it) },
                onToggleSort = { vm.toggleSort(it) },
                onToggleItem = { vm.toggleItemSelection(it) },
                onSafFilesPicked = { vm.addSafSelection(it) },
                onSafTreePicked = { vm.addSafTree(it) },
                onClearCart = { vm.clearCart() },
                onContinue = { vm.confirmCartAndProceed() },
                onBack = {
                    if (vm.hasActivePairedSession) vm.navigateTo(AppScreen.Transferring)
                    else vm.navigateTo(AppScreen.Home)
                }
            )
            AppScreen.SenderPairing -> SenderPairingScreen(
                code = senderCode,
                remainingMs = senderRemMs,
                feedback = pairingFeedback,
                network = network,
                onToggleHotspot = { vm.toggleLocalHotspot() },
                onStartWifiDirect = { vm.startWifiDirect5Ghz() },
                onClearNetError = { vm.clearNetworkError() },
                onRotateCode = { vm.rotateSenderCode() },
                onCancel = { vm.endSession(keepScreen = false) }
            )
            AppScreen.ReceiverPairing -> ReceiverPairingScreen(
                peers = peers,
                feedback = pairingFeedback,
                network = network,
                onToggleHotspot = { vm.toggleLocalHotspot() },
                onStartWifiDirect = { vm.startWifiDirect5Ghz() },
                onClearNetError = { vm.clearNetworkError() },
                onScanWifiQr = { vm.connectViaWifiQr(it) },
                onSubmitCode = { peer, code -> vm.joinSenderWithCode(peer, code) },
                onCancel = { vm.endSession(keepScreen = false) }
            )
            AppScreen.Transferring -> TransferringScreen(
                state = transfer,
                onCancel = { vm.cancelActiveTransfer() },
                onSendMore = { vm.openSendFlow() },
                onFinish = { vm.endSession(keepScreen = false) }
            )
            AppScreen.ShareWeb -> ShareWebScreen(
                state = webShare,
                network = network,
                onToggleHotspot = { vm.toggleLocalHotspot() },
                onStartWifiDirect = { vm.startWifiDirect5Ghz() },
                onClearNetError = { vm.clearNetworkError() },
                onTogglePin = { vm.toggleWebSharePin(it) },
                onBack = { vm.stopWebShare(); vm.navigateTo(AppScreen.Home) }
            )
            AppScreen.History -> HistoryScreen(
                records = history,
                onClear = { vm.clearHistory() },
                onBack = { vm.navigateTo(AppScreen.Home) }
            )
            AppScreen.Settings -> SettingsScreen(
                settings = settings,
                onRename = { vm.updateDeviceName(it) },
                onChangeDest = { vm.updateDestinationPath(it) },
                onChangeTheme = { vm.updateThemeMode(it) },
                onBack = { vm.navigateTo(AppScreen.Home) }
            )
        }
    }
}
