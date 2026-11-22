package com.localdrop.protocol

import com.localdrop.crypto.PathSanitizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

object FrameType {
    const val MANIFEST: Byte = 1       // sender → receiver : liste des fichiers
    const val MANIFEST_ACK: Byte = 2   // receiver → sender : accepté / espace insuffisant / reprise
    const val FILE_BEGIN: Byte = 3     // [fileId:4][offset:8]
    const val FILE_DATA: Byte = 4      // [fileId:4][offset:8][octets bruts, 512 Ko]
    const val FILE_END: Byte = 5       // [fileId:4][sha256:32]
    const val FILE_RESULT: Byte = 6    // [fileId:4][ok:1][message]
    const val CANCEL: Byte = 7
    const val BATCH_END: Byte = 8      // fin d'un lot (le tunnel reste ouvert pour « Envoyer d'autres fichiers »)
    const val SESSION_END: Byte = 9    // « Terminer » : fermeture du tunnel
}

object Limits {
    const val CHUNK = 512 * 1024                    // tampon de lecture/écriture : 512 Ko
    const val LARGE_FILE = 10L * 1024 * 1024         // seuil gros fichier / petits fichiers
    const val MAX_ENTRIES = 200_000
    const val MAX_WORKERS = 4
}

data class FileEntry(val id: Int, val relPath: String, val size: Long)

data class ResumeInfo(val offset: Long, val partialSha256: ByteArray)

data class Manifest(val entries: List<FileEntry>) {
    val totalBytes get() = entries.sumOf { it.size }

    fun encode(): ByteArray = ByteArrayOutputStream().also { bos ->
        DataOutputStream(bos).use { o ->
            o.writeInt(entries.size)
            entries.forEach { o.writeInt(it.id); o.writeUTF(it.relPath); o.writeLong(it.size) }
        }
    }.toByteArray()

    companion object {
        /** Décodage défensif : tout chemin est assaini, toute taille bornée. */
        fun decode(bytes: ByteArray): Manifest = DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            val n = i.readInt()
            if (n !in 0..Limits.MAX_ENTRIES) throw IOException("manifeste invalide")
            val seen = HashSet<Int>()
            Manifest(List(n) {
                val id = i.readInt()
                val path = PathSanitizer.sanitizeRelativePath(i.readUTF())
                val size = i.readLong()
                if (size < 0 || !seen.add(id)) throw IOException("entrée de manifeste invalide")
                FileEntry(id, path, size)
            })
        }
    }
}

data class ManifestAck(
    val accepted: Boolean,
    val reason: String,
    val workers: Int,
    val resume: Map<Int, ResumeInfo>,
) {
    fun encode(): ByteArray = ByteArrayOutputStream().also { bos ->
        DataOutputStream(bos).use { o ->
            o.writeBoolean(accepted); o.writeUTF(reason); o.writeByte(workers)
            o.writeInt(resume.size)
            resume.forEach { (id, r) -> o.writeInt(id); o.writeLong(r.offset); o.write(r.partialSha256) }
        }
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): ManifestAck = DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            val ok = i.readBoolean(); val reason = i.readUTF(); val w = i.readUnsignedByte().coerceIn(1, Limits.MAX_WORKERS)
            val n = i.readInt().also { if (it !in 0..Limits.MAX_ENTRIES) throw IOException("ack invalide") }
            val map = HashMap<Int, ResumeInfo>()
            repeat(n) { val id = i.readInt(); val off = i.readLong(); map[id] = ResumeInfo(off, ByteArray(32).also(i::readFully)) }
            ManifestAck(ok, reason, w, map)
        }
    }
}
