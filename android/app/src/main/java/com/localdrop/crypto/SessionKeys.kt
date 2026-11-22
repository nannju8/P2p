package com.localdrop.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Clés de session maintenues UNIQUEMENT en RAM. [destroy] les écrase (Perfect Forward Secrecy). */
class SessionKeys(private val sessionKey: ByteArray, private val confirmKey: ByteArray) {

    /** Clés directionnelles pour un canal donné. `salt` doit être unique par connexion. */
    class ChannelKeys(val senderToReceiver: ByteArray, val receiverToSender: ByteArray) {
        fun destroy() { Arrays.fill(senderToReceiver, 0); Arrays.fill(receiverToSender, 0) }
    }

    @Synchronized
    fun channelKeys(salt: ByteArray): ChannelKeys {
        check(!destroyed) { "clés détruites" }
        return ChannelKeys(
            Hkdf.derive(sessionKey, salt, "ld/s2r".toByteArray(), 32),
            Hkdf.derive(sessionKey, salt, "ld/r2s".toByteArray(), 32),
        )
    }

    /** Preuve d'appartenance à la session pour les connexions workers parallèles. */
    @Synchronized
    fun workerProof(nonce: ByteArray): ByteArray {
        check(!destroyed) { "clés détruites" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(confirmKey, "HmacSHA256"))
        mac.update("ld/worker".toByteArray())
        return mac.doFinal(nonce)
    }

    fun verifyWorkerProof(nonce: ByteArray, proof: ByteArray) =
        MessageDigest.isEqual(workerProof(nonce), proof) // comparaison en temps constant

    @Volatile private var destroyed = false

    @Synchronized
    fun destroy() {
        Arrays.fill(sessionKey, 0); Arrays.fill(confirmKey, 0); destroyed = true
    }

    companion object {
        fun randomNonce(n: Int = 16) = ByteArray(n).also { SecureRandom().nextBytes(it) }
    }
}
