package com.localdrop.protocol

import android.content.Context
import android.media.MediaScannerConnection
import android.os.StatFs
import com.localdrop.crypto.PathSanitizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Écriture sécurisée côté récepteur :
 *  .part temporaire → SHA-256 vérifié → renommage atomique → MediaScanner.
 * Les octets reçus ne peuvent jamais dépasser la taille annoncée.
 */
class FileReceiver(
    private val destinationDir: File,
    private val manifest: Manifest,
    private val context: Context? = null,
    private val freeBytes: (File) -> Long = { StatFs(it.path).availableBytes },
) {
    private class Active(
        val entry: FileEntry, val part: File, val target: File,
        val out: FileOutputStream, val digest: MessageDigest, var received: Long,
    )

    private val byId = manifest.entries.associateBy { it.id }
    private val active = ConcurrentHashMap<Int, Active>()

    private fun targetOf(e: FileEntry) = PathSanitizer.resolveSafe(destinationDir, e.relPath)
    private fun partOf(e: FileEntry): File = targetOf(e).let { File(it.parentFile, ".${it.name}.${e.size}.part") }

    /** Vérifie l'espace libre (StatFs) et prépare les infos de reprise. */
    fun prepare(workers: Int): ManifestAck {
        destinationDir.mkdirs()
        val resume = HashMap<Int, ResumeInfo>()
        var needed = 0L
        for (e in manifest.entries) {
            val part = partOf(e)
            val have = if (part.isFile && part.length() in 1 until e.size) part.length() else 0L
            if (have > 0) resume[e.id] = ResumeInfo(have, sha256Prefix(part, have))
            needed += e.size - have
        }
        val free = freeBytes(destinationDir)
        if (needed + SAFETY_MARGIN > free)
            return ManifestAck(false, "Espace insuffisant : $needed octets requis, $free disponibles", 1, emptyMap())
        return ManifestAck(true, "", workers, resume)
    }

    fun begin(fileId: Int, offset: Long) {
        val e = byId[fileId] ?: throw IOException("fichier inconnu")
        val part = partOf(e)
        part.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val start = if (part.isFile && offset > 0 && part.length() >= offset) offset else 0L
        if (start > 0) { // ré-alimente le hash avec le préfixe déjà reçu
            part.inputStream().use { i -> val b = ByteArray(Limits.CHUNK); var left = start
                while (left > 0) { val r = i.read(b, 0, minOf(b.size.toLong(), left).toInt()); if (r < 0) break; digest.update(b, 0, r); left -= r } }
        }
        RandomAccessFile(part, "rw").use { it.setLength(start) }
        active[fileId] = Active(e, part, targetOf(e), FileOutputStream(part, true), digest, start)
    }

    fun write(fileId: Int, offset: Long, buf: ByteArray, off: Int, len: Int) {
        val a = active[fileId] ?: throw IOException("transfert non démarré")
        if (offset != a.received) throw IOException("offset inattendu")
        if (a.received + len > a.entry.size) throw SecurityException("dépassement de la taille annoncée") // anti-flood
        a.out.write(buf, off, len)
        a.digest.update(buf, off, len)
        a.received += len
    }

    /** @return chemin final, ou lève IOException (le .part corrompu est alors supprimé). */
    fun finish(fileId: Int, expectedSha256: ByteArray): File {
        val a = active.remove(fileId) ?: throw IOException("transfert non démarré")
        try {
            a.out.flush(); a.out.fd.sync(); a.out.close()
            if (a.received != a.entry.size) throw IOException("taille incomplète")
            if (!MessageDigest.isEqual(a.digest.digest(), expectedSha256)) throw IOException("empreinte SHA-256 différente")
        } catch (e: Exception) {
            runCatching { a.out.close() }; a.part.delete(); throw if (e is IOException) e else IOException(e)
        }
        val finalFile = PathSanitizer.uniqueFile(a.target)
        Files.move(a.part.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        context?.let { MediaScannerConnection.scanFile(it, arrayOf(finalFile.path), null, null) } // visible dans la Galerie
        return finalFile
    }

    /** Annulation utilisateur : nettoie les .part. (Sur coupure réseau on les garde pour la reprise.) */
    fun abortAll(deletePartials: Boolean) {
        active.values.forEach { runCatching { it.out.close() }; if (deletePartials) it.part.delete() }
        active.clear()
        if (deletePartials) manifest.entries.forEach { partOf(it).delete() }
    }

    private fun sha256Prefix(f: File, n: Long): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(Limits.CHUNK); var left = n
            while (left > 0) { val r = i.read(b, 0, minOf(b.size.toLong(), left).toInt()); if (r < 0) break; d.update(b, 0, r); left -= r } }
        return d.digest()
    }

    companion object { const val SAFETY_MARGIN = 16L * 1024 * 1024 }
}
