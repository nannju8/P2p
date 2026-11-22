package com.localdrop.network

import android.content.ContentResolver
import android.net.Uri
import com.localdrop.crypto.Frame
import com.localdrop.crypto.SecureChannel
import com.localdrop.crypto.SessionKeys
import com.localdrop.protocol.FileEntry
import com.localdrop.protocol.FileReceiver
import com.localdrop.protocol.FrameType
import com.localdrop.protocol.Limits
import com.localdrop.protocol.Manifest
import com.localdrop.protocol.ManifestAck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue

class TransferRefused(message: String) : IOException(message)

/** Callback de progression (thread-safe côté appelant). */
fun interface Progress {
    fun onBytes(fileId: Int, delta: Long)
    companion object { val NONE = Progress { _, _ -> } }
}

/**
 * Stratégie :
 *  - fichiers ≥ 10 Mo : 1 à 2 flux TCP (gros tampons) ;
 *  - lots de fichiers < 10 Mo : 3 à 4 connexions workers parallèles (clé de session partagée) ;
 *  - lecture/écriture en streaming par blocs de 512 Ko : JAMAIS un fichier entier en RAM ;
 *  - SHA-256 calculé à la volée des deux côtés, comparé en fin de fichier.
 */
object TransferEngine {

    fun planWorkers(entries: List<FileEntry>): Int = when {
        entries.isEmpty() -> 1
        entries.all { it.size >= Limits.LARGE_FILE } -> minOf(2, entries.size)
        else -> minOf(Limits.MAX_WORKERS, entries.size)
    }

    // ───────────────────────────────── ENVOYEUR ─────────────────────────────────────
    suspend fun sendBatch(
        control: SecureChannel,
        pairing: SenderPairing,
        keys: SessionKeys,
        entries: List<FileEntry>,
        sources: Map<Int, Uri>,
        resolver: ContentResolver,
        progress: Progress,
        io: CoroutineDispatcher = Dispatchers.IO,
    ) = withContext(io) {
        control.send(FrameType.MANIFEST, Manifest(entries).encode())
        val ackFrame = control.receive()
        if (ackFrame.type != FrameType.MANIFEST_ACK) throw IOException("réponse inattendue")
        val ack = ManifestAck.decode(ackFrame.payload)
        if (!ack.accepted) throw TransferRefused(ack.reason)

        val channels = listOf(control) + (1 until ack.workers).map { pairing.acceptWorker(keys) }
        val queue = ConcurrentLinkedQueue(entries.sortedByDescending { it.size })
        try {
            coroutineScope {
                channels.forEach { ch ->
                    launch(io) {
                        while (true) {
                            ensureActive()
                            val e = queue.poll() ?: break
                            sendOne(
                                ch = ch,
                                e = e,
                                uri = sources.getValue(e.id),
                                resolver = resolver,
                                resume = ack.resume[e.id]?.let { it.offset to it.partialSha256 },
                                progress = progress
                            )
                        }
                    }
                }
            }
            channels.forEach { it.send(FrameType.BATCH_END) }
        } finally {
            channels.drop(1).forEach { it.close() }
        }
    }

    private fun sendOne(
        ch: SecureChannel,
        e: FileEntry,
        uri: Uri,
        resolver: ContentResolver,
        resume: Pair<Long, ByteArray>?,
        progress: Progress
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val pfd = resolver.openFileDescriptor(uri, "r")
            ?: throw IOException("impossible d'ouvrir ${e.relPath}")

        pfd.use { fd ->
            FileInputStream(fd.fileDescriptor).use { fis ->
                val channel = fis.channel
                var offset = 0L
                if (resume != null && resume.first in 1 until e.size) {
                    val d = MessageDigest.getInstance("SHA-256")
                    val b = ByteArray(Limits.CHUNK)
                    var left = resume.first
                    channel.position(0)
                    while (left > 0) {
                        val r = fis.read(b, 0, minOf(b.size.toLong(), left).toInt())
                        if (r < 0) break
                        d.update(b, 0, r)
                        left -= r
                    }
                    val prefixHash = (d.clone() as MessageDigest).digest()
                    if (MessageDigest.isEqual(prefixHash, resume.second)) {
                        offset = resume.first
                        digest.reset()
                        channel.position(0)
                        var l2 = offset
                        while (l2 > 0) {
                            val r = fis.read(b, 0, minOf(b.size.toLong(), l2).toInt())
                            if (r < 0) break
                            digest.update(b, 0, r)
                            l2 -= r
                        }
                    }
                }

                channel.position(offset)
                ch.send(FrameType.FILE_BEGIN, ByteBuffer.allocate(12).putInt(e.id).putLong(offset).array())

                val buf = ByteArray(12 + Limits.CHUNK)
                var pos = offset
                while (pos < e.size) {
                    val n = fis.read(buf, 12, minOf(Limits.CHUNK.toLong(), e.size - pos).toInt())
                    if (n < 0) throw IOException("source tronquée : ${e.relPath}")
                    ByteBuffer.wrap(buf).putInt(e.id).putLong(pos)
                    digest.update(buf, 12, n)
                    ch.send(FrameType.FILE_DATA, buf, 0, 12 + n)
                    pos += n
                    progress.onBytes(e.id, n.toLong())
                }

                ch.send(FrameType.FILE_END, ByteBuffer.allocate(36).putInt(e.id).put(digest.digest()).array())
                val res = ch.receive()
                if (res.type != FrameType.FILE_RESULT || res.payload.size < 5 || res.payload[4].toInt() != 1) {
                    throw IOException("le récepteur a rejeté ${e.relPath}")
                }
            }
        }
    }

    // ───────────────────────────────── RÉCEPTEUR ────────────────────────────────────
    suspend fun receiveBatch(
        control: SecureChannel,
        manifestFrame: Frame,
        newReceiver: (Manifest) -> FileReceiver,
        openWorker: () -> SecureChannel,
        progress: Progress,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): List<File> = withContext(io) {
        val manifest = Manifest.decode(manifestFrame.payload)
        val receiver = newReceiver(manifest)
        val ack = receiver.prepare(planWorkers(manifest.entries))
        control.send(FrameType.MANIFEST_ACK, ack.encode())
        if (!ack.accepted) throw TransferRefused(ack.reason)

        val finished = Collections.synchronizedList(mutableListOf<File>())
        val workers = listOf(control) + (1 until ack.workers).map { openWorker() }
        try {
            coroutineScope {
                workers.forEach { ch ->
                    launch(io) { receiveLoop(ch, receiver, finished, progress) }
                }
            }
        } catch (c: CancellationException) {
            receiver.abortAll(deletePartials = true)
            throw c
        } catch (e: Exception) {
            receiver.abortAll(deletePartials = false)
            throw e
        } finally {
            workers.drop(1).forEach { it.close() }
        }
        finished
    }

    private fun receiveLoop(
        ch: SecureChannel,
        r: FileReceiver,
        finished: MutableList<File>,
        progress: Progress
    ) {
        while (true) {
            val f = ch.receive()
            val bb = ByteBuffer.wrap(f.payload)
            when (f.type) {
                FrameType.FILE_BEGIN -> r.begin(bb.int, bb.long)
                FrameType.FILE_DATA -> {
                    val id = bb.int
                    val off = bb.long
                    r.write(id, off, f.payload, 12, f.payload.size - 12)
                    progress.onBytes(id, (f.payload.size - 12).toLong())
                }
                FrameType.FILE_END -> {
                    val id = bb.int
                    val sha = ByteArray(32).also(bb::get)
                    val ok = runCatching { finished += r.finish(id, sha) }.isSuccess
                    ch.send(FrameType.FILE_RESULT, ByteBuffer.allocate(5).putInt(id).put(if (ok) 1 else 0).array())
                }
                FrameType.BATCH_END -> return
                FrameType.CANCEL -> throw CancellationException("annulé par l'envoyeur")
                else -> throw IOException("trame inattendue ${f.type}")
            }
        }
    }
}
