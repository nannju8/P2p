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
import io.ktor.server.request.origin
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

data class ShareStatus(val running: Boolean = false, val port: Int = 0, val downloads: Int = 0, val activeBytes: Long = 0, val totalBytes: Long = 0, val lastActivity: Long = 0)

/**
 * « MODE INVITE WEB » – ISOLATION STRICTE : exactement 2 routes publiques.
 *   GET /          → page HTML statique (CSS inline, aucune ressource externe)
 *   GET /download  → flux binaire de NOTRE apk (applicationInfo.sourceDir), rien d'autre.
 * Aucun chemin n'est dérivé de la requête : impossible de lire un autre fichier du téléphone.
 */
class GuestWebServer(private val context: Context, private val pin: String? = null) {
    private var engine: ApplicationEngine? = null
    private val _status = MutableStateFlow(ShareStatus())
    val status: StateFlow<ShareStatus> = _status
    private val badPins = ConcurrentHashMap<String, Int>()

    private val apk: File = File(context.applicationInfo.sourceDir)          // APK universel unique
    private val appName = context.applicationInfo.loadLabel(context.packageManager).toString()
    private val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"

    /** Port 8080, repli 8081..8085 si occupé. Retourne le port réel. */
    fun start(): Int {
        for (port in 8080..8085) {
            try {
                val e = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    routing {
                        get("/") { touch(); call.respondText(GuestPage.html(appName, version, apk.length(), pin != null), ContentType.Text.Html) }
                        get("/download") {
                            touch()
                            val ip = call.request.origin.remoteHost
                            if (pin != null) {
                                if ((badPins[ip] ?: 0) >= 3) return@get call.respondText("Trop d'essais.", status = HttpStatusCode.Forbidden)
                                val given = call.request.queryParameters["pin"].orEmpty()
                                if (!MessageDigest.isEqual(given.toByteArray(), pin.toByteArray())) {
                                    badPins.merge(ip, 1, Int::plus)
                                    return@get call.respondText("Code incorrect (${3 - (badPins[ip] ?: 0)} essai(s) restant(s)).", status = HttpStatusCode.Unauthorized)
                                }
                            }
                            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${appName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk\"")
                            call.respond(ApkContent(apk))
                        }
                    }
                }
                e.start(wait = false); engine = e
                _status.value = ShareStatus(true, port, totalBytes = apk.length(), lastActivity = System.currentTimeMillis())
                return port
            } catch (_: java.net.BindException) { /* port occupé → suivant */ }
        }
        error("Aucun port libre entre 8080 et 8085")
    }

    private fun touch() { _status.value = _status.value.copy(lastActivity = System.currentTimeMillis()) }

    /** Contenu sortant avec Content-Length exact + comptage de progression pour l'écran hôte. */
    private inner class ApkContent(private val f: File) : OutgoingContent.WriteChannelContent() {
        override val contentLength = f.length()
        override val contentType = ContentType("application", "vnd.android.package-archive")
        override suspend fun writeTo(channel: ByteWriteChannel) {
            val buf = ByteArray(256 * 1024); var sent = 0L
            FileInputStream(f).use { i ->
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    channel.writeFully(buf, 0, n); sent += n
                    _status.value = _status.value.copy(activeBytes = sent, lastActivity = System.currentTimeMillis())
                }
            }
            _status.value = _status.value.copy(downloads = _status.value.downloads + 1, activeBytes = 0)
        }
    }

    /** Appelé à la sortie de l'écran « Partager l'application » ou après 5 min d'inactivité. */
    fun stop() { engine?.stop(500, 1000); engine = null; _status.value = ShareStatus() }

    fun idleMs() = System.currentTimeMillis() - _status.value.lastActivity
}
