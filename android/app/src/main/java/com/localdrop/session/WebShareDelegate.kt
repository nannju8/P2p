package com.localdrop.session

import android.content.Context
import com.localdrop.network.HotspotController
import com.localdrop.network.NetworkBinder
import com.localdrop.web.GuestWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom

class WebShareDelegate(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val binder: NetworkBinder,
    private val hotspot: HotspotController,
    private val onStopHotspotUi: () -> Unit,
) {
    private val _state = MutableStateFlow(WebShareUiState())
    val state: StateFlow<WebShareUiState> = _state.asStateFlow()

    private var server: GuestWebServer? = null
    private var watchJob: Job? = null

    fun start() {
        if (server?.status?.value?.running == true) return
        val generatedPin = "%04d".format(SecureRandom().nextInt(10_000))
        val pinEnabled = _state.value.pinEnabled
        val srv = GuestWebServer(ctx, if (pinEnabled) generatedPin else null).also { server = it }

        scope.launch(Dispatchers.IO) {
            try {
                binder.acquireWifi(1_500)
                val port = srv.start()
                val ip = binder.localIpv4()?.hostAddress ?: "192.168.43.1"
                _state.update {
                    it.copy(active = true, url = "http://$ip:$port", pinCode = generatedPin, error = null)
                }
                watchJob?.cancel()
                watchJob = scope.launch {
                    launch {
                        srv.status.collect { st ->
                            _state.update { it.copy(status = st, active = st.running) }
                        }
                    }
                    // Extinction automatique après 5 min d'inactivité
                    while (isActive) {
                        delay(5_000)
                        if (srv.idleMs() > 5 * 60_000L && srv.status.value.activeBytes == 0L) {
                            stop()
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(active = false, error = e.message ?: "Impossible de démarrer le serveur web") }
            }
        }
    }

    fun updateIpIfRunning(ip: String) {
        val srv = server ?: return
        if (srv.status.value.running && ip.isNotBlank()) {
            _state.update { it.copy(url = "http://$ip:${srv.status.value.port}") }
        }
    }

    fun togglePin(enabled: Boolean) {
        val pin = _state.value.pinCode.ifBlank { "%04d".format(SecureRandom().nextInt(10_000)) }
        server?.pin = if (enabled) pin else null
        _state.update { it.copy(pinEnabled = enabled, pinCode = pin) }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        val srv = server
        server = null
        scope.launch(Dispatchers.IO) { srv?.stop() }
        hotspot.stopHotspot()
        onStopHotspotUi()
        _state.update { it.copy(active = false) }
    }
}
