package com.localdrop.network

import com.localdrop.crypto.PakeHandshake
import com.localdrop.crypto.SecureChannel
import com.localdrop.crypto.SessionKeys
import com.localdrop.protocol.PairingGuard
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/** Octet d'ouverture de connexion. */
private const val KIND_PAIR: Int = 1
private const val KIND_WORKER: Int = 2
private const val STATUS_OK = 0
private const val STATUS_BAD_CODE = 1
private const val STATUS_LOCKED = 2
private const val STATUS_EXPIRED = 3

sealed interface PairResult {
    class Success(val control: SecureChannel, val keys: SessionKeys) : PairResult
    data class BadCode(val remaining: Int) : PairResult
    data class Locked(val cooldownMs: Long) : PairResult
    object Expired : PairResult
}

private fun DataOutputStream.writeBlob(b: ByteArray) { writeShort(b.size); write(b); flush() }
private fun DataInputStream.readBlob(): ByteArray {
    val n = readUnsignedShort(); if (n > 4096) throw IOException("blob trop grand"); return ByteArray(n).also(::readFully)
}

/** ENVOYEUR = serveur : il génère le code (PairingGuard) mais ne voit jamais celui tapé : il le vérifie via PAKE. */
class SenderPairing(private val server: ServerSocket, private val guard: PairingGuard, private val binder: NetworkBinder) {

    /** Boucle d'acceptation : retourne dès qu'un récepteur est appairé avec succès. */
    fun awaitPairing(onResult: (PairResult) -> Unit = {}): PairResult.Success {
        while (true) {
            val socket = server.accept()
            try {
                binder.tune(socket, bulk = true)
                socket.soTimeout = 20_000
                val inp = DataInputStream(socket.getInputStream().buffered())
                val out = DataOutputStream(socket.getOutputStream().buffered())
                if (inp.readUnsignedByte() != KIND_PAIR) { socket.close(); continue }
                val r = handshake(socket, inp, out)
                onResult(r)
                if (r is PairResult.Success) { socket.soTimeout = 0; return r }
                socket.close()
            } catch (e: Exception) { runCatching { socket.close() } }
        }
    }

    private fun handshake(socket: Socket, inp: DataInputStream, out: DataOutputStream): PairResult {
        val peer = socket.inetAddress.hostAddress ?: "?"
        guard.blockedFor(peer).takeIf { it > 0 }?.let { out.writeByte(STATUS_LOCKED); out.writeLong(it); out.flush(); return PairResult.Locked(it) }
        val code = guard.codeForHandshake()
            ?: run { out.writeByte(STATUS_EXPIRED); out.flush(); return PairResult.Expired }
        val pake = PakeHandshake("sender", "receiver", code)
        java.util.Arrays.fill(code, '\u0000')

        out.writeByte(STATUS_OK); out.flush()                       // « vous pouvez commencer le PAKE »
        out.writeBlob(pake.round1()); pake.onRound1(inp.readBlob())
        out.writeBlob(pake.round2()); pake.onRound2(inp.readBlob())
        out.writeBlob(pake.round3())
        val ok = runCatching { pake.onRound3(inp.readBlob()) }.isSuccess   // faux code ⇒ échec de confirmation

        if (!ok) {
            val f = guard.recordFailure(peer)
            return when (f) {
                is PairingGuard.Failure.Retry -> { out.writeByte(STATUS_BAD_CODE); out.writeByte(f.remaining); out.flush(); PairResult.BadCode(f.remaining) }
                is PairingGuard.Failure.Locked -> { out.writeByte(STATUS_LOCKED); out.writeLong(f.cooldownMs); out.flush(); PairResult.Locked(f.cooldownMs) }
            }
        }
        guard.recordSuccess(peer)
        out.writeByte(STATUS_OK); out.flush()
        val keys = pake.deriveSessionKeys()
        val ck = keys.channelKeys("control".toByteArray())
        val chan = SecureChannel(inp, out, ck.senderToReceiver, ck.receiverToSender,
            SecureChannel.SENDER_PREFIX, SecureChannel.RECEIVER_PREFIX, socket)
        ck.destroy()
        return PairResult.Success(chan, keys)
    }

    /** Accepte une connexion worker : preuve HMAC de la clé de session + nonces frais ⇒ clés de canal uniques. */
    fun acceptWorker(keys: SessionKeys, timeoutMs: Int = 10_000): SecureChannel {
        server.soTimeout = timeoutMs
        try {
            while (true) {
                val socket = server.accept()
                try {
                    binder.tune(socket, bulk = true); socket.soTimeout = timeoutMs
                    val inp = DataInputStream(socket.getInputStream().buffered()); val out = DataOutputStream(socket.getOutputStream().buffered())
                    if (inp.readUnsignedByte() != KIND_WORKER) { socket.close(); continue }
                    val nonceR = inp.readBlob(); val proof = inp.readBlob()
                    if (nonceR.size != 16 || !keys.verifyWorkerProof(nonceR, proof)) { socket.close(); continue }
                    val nonceS = SessionKeys.randomNonce()
                    out.writeBlob(nonceS)
                    val ck = keys.channelKeys(nonceS + nonceR)
                    val chan = SecureChannel(inp, out, ck.senderToReceiver, ck.receiverToSender, SecureChannel.SENDER_PREFIX, SecureChannel.RECEIVER_PREFIX, socket)
                    ck.destroy(); socket.soTimeout = 0
                    return chan
                } catch (e: Exception) { runCatching { socket.close() } }
            }
        } finally { server.soTimeout = 0 }
    }
}

sealed interface JoinResult {
    class Success(val control: SecureChannel, val keys: SessionKeys) : JoinResult
    data class BadCode(val remaining: Int) : JoinResult
    data class Locked(val cooldownMs: Long) : JoinResult
    object Expired : JoinResult
}

/** RÉCEPTEUR = client : il SAISIT le code. */
class ReceiverPairing(private val binder: NetworkBinder) {

    fun join(peer: Peer, typedCode: CharArray): JoinResult {
        val socket = binder.connect(peer.host, peer.port)
        try {
            socket.soTimeout = 20_000
            val inp = DataInputStream(socket.getInputStream().buffered()); val out = DataOutputStream(socket.getOutputStream().buffered())
            out.writeByte(KIND_PAIR); out.flush()
            // L'envoyeur peut refuser d'emblée (cooldown actif / code expiré)
            when (inp.readUnsignedByte()) {
                STATUS_OK -> {}
                STATUS_LOCKED -> { val ms = inp.readLong(); socket.close(); return JoinResult.Locked(ms) }
                else -> { socket.close(); return JoinResult.Expired }
            }
            val pake = PakeHandshake("receiver", "sender", typedCode)
            java.util.Arrays.fill(typedCode, '\u0000')                // le code saisi ne reste pas en RAM
            out.writeBlob(pake.round1())
            pake.onRound1(inp.readBlob())
            out.writeBlob(pake.round2()); pake.onRound2(inp.readBlob())
            out.writeBlob(pake.round3())
            val okLocal = runCatching { pake.onRound3(inp.readBlob()) }.isSuccess
            return when (inp.readUnsignedByte()) {
                STATUS_OK -> if (okLocal) {
                    val keys = pake.deriveSessionKeys()
                    val ck = keys.channelKeys("control".toByteArray())
                    val chan = SecureChannel(inp, out, ck.receiverToSender, ck.senderToReceiver, SecureChannel.RECEIVER_PREFIX, SecureChannel.SENDER_PREFIX, socket)
                    ck.destroy(); socket.soTimeout = 0
                    JoinResult.Success(chan, keys)
                } else { socket.close(); JoinResult.BadCode(0) }
                STATUS_BAD_CODE -> JoinResult.BadCode(inp.readUnsignedByte()).also { socket.close() }
                STATUS_LOCKED -> JoinResult.Locked(inp.readLong()).also { socket.close() }
                else -> JoinResult.Expired.also { socket.close() }
            }
        } catch (e: IOException) { runCatching { socket.close() }; throw e }
    }

    fun openWorker(peer: Peer, keys: SessionKeys): SecureChannel {
        val socket = binder.connect(peer.host, peer.port)
        val inp = DataInputStream(socket.getInputStream().buffered()); val out = DataOutputStream(socket.getOutputStream().buffered())
        val nonceR = SessionKeys.randomNonce()
        out.writeByte(KIND_WORKER); out.writeBlob(nonceR); out.writeBlob(keys.workerProof(nonceR))
        val nonceS = inp.readBlob()
        val ck = keys.channelKeys(nonceS + nonceR)
        val chan = SecureChannel(inp, out, ck.receiverToSender, ck.senderToReceiver, SecureChannel.RECEIVER_PREFIX, SecureChannel.SENDER_PREFIX, socket)
        ck.destroy(); return chan
    }
}
