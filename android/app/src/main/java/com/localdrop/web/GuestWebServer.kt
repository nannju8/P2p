package com.localdrop.web

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class ShareStatus(
    val running: Boolean = false,
    val port: Int = 0,
    val downloads: Int = 0,
    val activeBytes: Long = 0,
    val totalBytes: Long = 0,
    val lastActivity: Long = 0,
)

class GuestWebServer(private val context: Context, @Volatile var pin: String? = null) {
    private var engine: ApplicationEngine? = null
    private val _status = MutableStateFlow(ShareStatus())
    val status: StateFlow<ShareStatus> = _status
    private val badPins = ConcurrentHashMap<String, Int>()

    private val apk: File = File(context.applicationInfo.sourceDir)
    val apkSizeBytes: Long get() = apk.length()
    val appName: String = context.applicationInfo.loadLabel(context.packageManager).toString()
    val version: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

    fun start(): Int {
        if (engine != null && _status.value.running) return _status.value.port
        for (port in 8080..8085) {
            try {
                val e = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    routing {
                        get("/") {
                            touch()
                            call.respondText(
                                GuestPage.html(appName, version, apk.length(), pin != null),
                                ContentType.Text.Html
                            )
                        }
                        get("/download") {
                            touch()
                            val activePin = pin
                            val ip = call.request.local.remoteHost
                            if (activePin != null) {
                                val tries = badPins[ip] ?: 0
                                if (tries >= 3) {
                                    return@get call.respondText(
                                        GuestPage.html(appName, version, apk.length(), false, "Accès bloqué après 3 codes incorrects."),
                                        ContentType.Text.Html,
                                        HttpStatusCode.Forbidden
                                    )
                                }
                                val given = call.request.queryParameters["pin"].orEmpty()
                                if (!MessageDigest.isEqual(given.toByteArray(), activePin.toByteArray())) {
                                    val newTries = badPins.merge(ip, 1, Int::plus) ?: 1
                                    val msg = "Code PIN incorrect (${(3 - newTries).coerceAtLeast(0)} essai(s) restant(s))."
                                    return@get call.respondText(
                                        GuestPage.html(appName, version, apk.length(), true, msg),
                                        ContentType.Text.Html,
                                        HttpStatusCode.Unauthorized
                                    )
                                }
                                badPins.remove(ip)
                            }
                            call.response.header(
                                HttpHeaders.ContentDisposition,
                                "attachment; filename=\"${appName.replace(Regex("[^A-Za-z0-9._-]"), "_")}-v$version.apk\""
                            )
                            call.respond(ApkContent(apk))
                        }
                    }
                }
                e.start(wait = false)
                engine = e
                _status.value = ShareStatus(true, port, totalBytes = apk.length(), lastActivity = System.currentTimeMillis())
                return port
            } catch (_: java.net.BindException) { }
        }
        error("Aucun port libre entre 8080 et 8085")
    }

    private fun touch() { _status.value = _status.value.copy(lastActivity = System.currentTimeMillis()) }

    private inner class ApkContent(private val f: File) : OutgoingContent.WriteChannelContent() {
        override val contentLength = f.length()
        override val contentType = ContentType("application", "vnd.android.package-archive")
        override suspend fun writeTo(channel: ByteWriteChannel) {
            val buf = ByteArray(256 * 1024)
            var sent = 0L
            FileInputStream(f).use { i ->
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    channel.writeFully(buf, 0, n)
                    sent += n
                    _status.value = _status.value.copy(activeBytes = sent, lastActivity = System.currentTimeMillis())
                }
            }
            _status.value = _status.value.copy(downloads = _status.value.downloads + 1, activeBytes = 0)
        }
    }

    fun stop() {
        runCatching { engine?.stop(300, 800) }
        engine = null
        badPins.clear()
        _status.value = ShareStatus()
    }

    fun idleMs() = System.currentTimeMillis() - _status.value.lastActivity
}
