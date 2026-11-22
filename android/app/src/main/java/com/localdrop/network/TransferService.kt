package com.localdrop.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** État global observé par l'UI Compose ET par la notification. */
data class TransferUiState(
    val active: Boolean = false, val totalBytes: Long = 0, val doneBytes: Long = 0,
    val speedBps: Long = 0, val etaSeconds: Long = -1, val cancelled: Boolean = false,
)

object TransferStore {
    private val _state = MutableStateFlow(TransferUiState())
    val state: StateFlow<TransferUiState> = _state
    @Volatile var cancelHook: (() -> Unit)? = null           // positionné par la session active

    fun begin(total: Long) { _state.value = TransferUiState(active = true, totalBytes = total) }
    fun update(done: Long, speed: Long) = _state.update {
        it.copy(doneBytes = done, speedBps = speed, etaSeconds = if (speed > 0) (it.totalBytes - done) / speed else -1)
    }
    fun finish(cancelled: Boolean = false) { _state.update { it.copy(active = false, cancelled = cancelled) } }
}

/**
 * ForegroundService : garde le transfert en vie écran éteint. WakeLock CPU + WifiLock
 * (LOW_LATENCY sur API 29+, HIGH_PERF sinon) acquis uniquement pendant le transfert actif.
 */
class TransferService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> { TransferStore.cancelHook?.invoke(); TransferStore.finish(cancelled = true); stopSelfClean(); return START_NOT_STICKY }
            else -> startTransfer()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startTransfer() {
        createChannel()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, buildNotification(0, 100), type)
        else startForeground(NOTIF_ID, buildNotification(0, 100))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalDrop:transfer").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "LocalDrop:wifi").apply { setReferenceCounted(false); acquire() }

        scope.launch {
            TransferStore.state.collect { s ->
                if (!s.active) { stopSelfClean(); return@collect }       // libère les verrous immédiatement
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification(s.doneBytes, s.totalBytes))
            }
        }
    }

    private fun buildNotification(done: Long, total: Long): Notification {
        val cancel = PendingIntent.getService(this, 0, Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Transfert en cours – $pct %")
            .setContentText("%.1f / %.1f Mo".format(done / 1048576.0, total / 1048576.0))
            .setProgress(100, pct, total == 0L).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Annuler", cancel)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Transferts", NotificationManager.IMPORTANCE_LOW))
    }

    private fun stopSelfClean() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null; wifiLock = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { scope.cancel(); stopSelfClean(); super.onDestroy() }

    companion object {
        const val ACTION_CANCEL = "com.localdrop.CANCEL"
        private const val CHANNEL = "transfer"; private const val NOTIF_ID = 42
        fun start(ctx: Context) = androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java))
    }
}
