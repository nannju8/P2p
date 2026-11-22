package com.localdrop.crypto

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Frame(val type: Byte, val payload: ByteArray, val length: Int = payload.size)

/**
 * Canal chiffré et authentifié : AES-256-GCM (accéléré matériellement sur ARM64 via Conscrypt).
 *
 *  Trame sur le fil :  [len:4 bytes BE] [ciphertext = AES-GCM( type:1 ‖ payload ) ‖ tag:16]
 *  - nonce 96 bits = [préfixe direction:4][compteur:8] → jamais réutilisé, strictement croissant ;
 *    tout rejeu / réordonnancement / troncature fait échouer le tag GCM ;
 *  - l'en-tête de longueur est authentifié comme AAD ;
 *  - une clé distincte par direction ET par connexion (cf. SessionKeys.channelKeys).
 */
class SecureChannel(
    private val input: InputStream,
    private val output: OutputStream,
    sendKey: ByteArray,
    recvKey: ByteArray,
    private val sendPrefix: Int,
    private val recvPrefix: Int,
    private val closeable: Closeable? = null,
) : Closeable {

    private val sendKeyBytes = sendKey.copyOf()
    private val recvKeyBytes = recvKey.copyOf()
    private val sendSpec = SecretKeySpec(sendKeyBytes, "AES")
    private val recvSpec = SecretKeySpec(recvKeyBytes, "AES")
    private val sendCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val recvCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var sendCounter = 0L
    private var recvCounter = 0L
    private val sendLock = Any()
    private val recvLock = Any()

    private var plain = ByteArray(0)
    private var wire = ByteArray(0)

    private fun nonce(prefix: Int, counter: Long): ByteArray {
        require(counter in 0 until (1L shl 48)) { "compteur de nonce épuisé" }
        return ByteBuffer.allocate(12).putInt(prefix).putLong(counter).array()
    }

    fun send(type: Byte, payload: ByteArray = EMPTY, off: Int = 0, len: Int = payload.size - off) {
        require(len <= MAX_PLAINTEXT) { "trame trop grande" }
        synchronized(sendLock) {
            val ptLen = 1 + len
            val ctLen = ptLen + TAG
            if (plain.size < ptLen) plain = ByteArray(ptLen.coerceAtLeast(64 * 1024))
            if (wire.size < 4 + ctLen) wire = ByteArray((4 + ctLen).coerceAtLeast(64 * 1024))
            plain[0] = type
            System.arraycopy(payload, off, plain, 1, len)
            ByteBuffer.wrap(wire).putInt(ctLen)

            sendCipher.init(Cipher.ENCRYPT_MODE, sendSpec, GCMParameterSpec(TAG * 8, nonce(sendPrefix, sendCounter++)))
            sendCipher.updateAAD(wire, 0, 4)
            val n = sendCipher.doFinal(plain, 0, ptLen, wire, 4)
            check(n == ctLen)
            output.write(wire, 0, 4 + ctLen)
            output.flush()
        }
    }

    /** @throws IOException si la trame est altérée, rejouée, hors limites ou si le flux est coupé. */
    fun receive(): Frame = synchronized(recvLock) {
        val header = ByteArray(4)
        readFully(header, 4)
        val ctLen = ByteBuffer.wrap(header).int
        if (ctLen < 1 + TAG || ctLen > MAX_PLAINTEXT + 1 + TAG) throw IOException("longueur de trame invalide")
        val ct = ByteArray(ctLen)
        readFully(ct, ctLen)
        try {
            recvCipher.init(Cipher.DECRYPT_MODE, recvSpec, GCMParameterSpec(TAG * 8, nonce(recvPrefix, recvCounter++)))
            recvCipher.updateAAD(header)
            val pt = recvCipher.doFinal(ct)
            Frame(pt[0], pt.copyOfRange(1, pt.size))
        } catch (e: GeneralSecurityException) {
            throw IOException("trame rejetée (authentification GCM échouée)", e)
        }
    }

    private fun readFully(buf: ByteArray, n: Int) {
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) throw EOFException("connexion fermée")
            read += r
        }
    }

    override fun close() {
        java.util.Arrays.fill(sendKeyBytes, 0)
        java.util.Arrays.fill(recvKeyBytes, 0)
        java.util.Arrays.fill(plain, 0)
        runCatching { closeable?.close() }
    }

    companion object {
        const val TAG = 16
        const val MAX_PLAINTEXT = 1024 * 1024 + 64      // 1 Mo de données + en-tête de fichier
        const val SENDER_PREFIX = 0x53454E44            // "SEND"
        const val RECEIVER_PREFIX = 0x52454345          // "RECE"
        private val EMPTY = ByteArray(0)
    }
}
