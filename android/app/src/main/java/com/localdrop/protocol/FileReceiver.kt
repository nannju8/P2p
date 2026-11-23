package com.localdrop.protocol

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.localdrop.crypto.PathSanitizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class FileReceiver(
    private val destinationDir: File,
    private val manifest: Manifest,
    private val context: Context? = null,
    private val freeBytes: (File) -> Long = { StatFs(it.path).availableBytes },
) {
    private class Active(
        val entry: FileEntry,
        val part: File,
        val target: File,
        val out: FileOutputStream,
        val digest: MessageDigest,
        var received: Long,
    )

    private val byId = manifest.entries.associateBy { it.id }
    private val active = ConcurrentHashMap<Int, Active>()

    private val publishViaMediaStore: Boolean =
        context != null && Build.VERSION.SDK_INT >= 29 && !hasDirectFileWriteAccess(context, destinationDir)

    private val workDir: File = if (publishViaMediaStore && context != null) {
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, ".ld_staging")
    } else {
        destinationDir
    }

    private fun targetOf(e: FileEntry) = PathSanitizer.resolveSafe(workDir, e.relPath)
    private fun partOf(e: FileEntry): File = targetOf(e).let { File(it.parentFile, ".${it.name}.${e.size}.part") }

    fun prepare(workers: Int): ManifestAck {
        if (!workDir.exists() && !workDir.mkdirs()) {
            return ManifestAck(false, "Dossier inaccessible : ${workDir.path}", 1, emptyMap())
        }
        val resume = HashMap<Int, ResumeInfo>()
        var needed = 0L
        var maxSingle = 0L
        for (e in manifest.entries) {
            val part = partOf(e)
            val have = if (part.isFile && part.length() in 1 until e.size) part.length() else 0L
            if (have > 0) resume[e.id] = ResumeInfo(have, sha256Prefix(part, have))
            needed += e.size - have
            if (e.size > maxSingle) maxSingle = e.size
        }
        val extra = if (publishViaMediaStore) maxSingle else 0L
        val free = freeBytes(workDir)
        if (needed + extra + SAFETY_MARGIN > free) {
            return ManifestAck(false, "Espace insuffisant : ${needed + extra} octets requis, $free disponibles", 1, emptyMap())
        }
        return ManifestAck(true, "", workers, resume)
    }

    fun begin(fileId: Int, offset: Long) {
        val e = byId[fileId] ?: throw IOException("fichier inconnu")
        val part = partOf(e)
        part.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val start = if (part.isFile && offset > 0 && part.length() >= offset) offset else 0L
        if (start > 0) {
            part.inputStream().use { i ->
                val b = ByteArray(Limits.CHUNK)
                var left = start
                while (left > 0) {
                    val r = i.read(b, 0, minOf(b.size.toLong(), left).toInt())
                    if (r < 0) break
                    digest.update(b, 0, r)
                    left -= r
                }
            }
        }
        RandomAccessFile(part, "rw").use { it.setLength(start) }
        active[fileId] = Active(e, part, targetOf(e), FileOutputStream(part, true), digest, start)
    }

    fun write(fileId: Int, offset: Long, buf: ByteArray, off: Int, len: Int) {
        val a = active[fileId] ?: throw IOException("transfert non démarré")
        if (offset != a.received) throw IOException("offset inattendu")
        if (a.received + len > a.entry.size) throw SecurityException("dépassement de la taille annoncée")
        a.out.write(buf, off, len)
        a.digest.update(buf, off, len)
        a.received += len
    }

    fun finish(fileId: Int, expectedSha256: ByteArray): File {
        val a = active.remove(fileId) ?: throw IOException("transfert non démarré")
        try {
            a.out.flush()
            if (a.entry.size >= Limits.LARGE_FILE) {
                runCatching { a.out.fd.sync() }
            }
            a.out.close()
            if (a.received != a.entry.size) throw IOException("taille incomplète")
            if (!MessageDigest.isEqual(a.digest.digest(), expectedSha256)) throw IOException("empreinte SHA-256 différente")
        } catch (e: Exception) {
            runCatching { a.out.close() }
            a.part.delete()
            throw if (e is IOException) e else IOException(e)
        }

        if (publishViaMediaStore && context != null && Build.VERSION.SDK_INT >= 29) {
            val published = publishToDownloadsMediaStore(context, a.part, a.entry.relPath)
            if (published != null) {
                a.part.delete()
                return published
            }
        }

        val finalFile = PathSanitizer.uniqueFile(a.target)
        finalFile.parentFile?.mkdirs()
        try {
            Files.move(a.part.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            Files.move(a.part.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        context?.let { MediaScannerConnection.scanFile(it, arrayOf(finalFile.path), null, null) }
        return finalFile
    }

    private fun publishToDownloadsMediaStore(ctx: Context, srcPart: File, rawRelPath: String): File? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            val clean = PathSanitizer.sanitizeRelativePath(rawRelPath)
            val slash = clean.lastIndexOf('/')
            val subDir = if (slash > 0) "/${clean.substring(0, slash)}" else ""
            val name = if (slash >= 0) clean.substring(slash + 1) else clean

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LocalDrop$subDir")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    val buf = ByteArray(Limits.CHUNK)
                    FileInputStream(srcPart).use { inp ->
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    out.flush()
                } ?: throw IOException("impossible d'ouvrir MediaStore")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                File(destinationDir, clean)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }.getOrNull()
    }

    fun abortAll(deletePartials: Boolean) {
        active.values.forEach {
            runCatching { it.out.close() }
            if (deletePartials) it.part.delete()
        }
        active.clear()
        if (deletePartials) manifest.entries.forEach { partOf(it).delete() }
    }

    private fun sha256Prefix(f: File, n: Long): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val b = ByteArray(Limits.CHUNK)
            var left = n
            while (left > 0) {
                val r = i.read(b, 0, minOf(b.size.toLong(), left).toInt())
                if (r < 0) break
                d.update(b, 0, r)
                left -= r
            }
        }
        return d.digest()
    }

    companion object {
        const val SAFETY_MARGIN = 16L * 1024 * 1024

        fun hasDirectFileWriteAccess(context: Context, dir: File): Boolean {
            val isAppOwned = runCatching {
                val appExt = context.getExternalFilesDir(null)?.canonicalPath
                val appInt = context.filesDir.canonicalPath
                val d = dir.canonicalPath
                (appExt != null && d.startsWith(appExt)) || d.startsWith(appInt)
            }.getOrDefault(false)
            if (isAppOwned) return true

            return when {
                Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
                Build.VERSION.SDK_INT == 29 -> Environment.isExternalStorageLegacy()
                else -> ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }

        fun resolveDestinationDir(context: Context, preferred: File? = null): File {
            val publicDir = preferred ?: File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "LocalDrop"
            )
            if (Build.VERSION.SDK_INT >= 29 || hasDirectFileWriteAccess(context, publicDir)) {
                return publicDir
            }
            return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "LocalDrop")
                .also { it.mkdirs() }
        }
    }
}
