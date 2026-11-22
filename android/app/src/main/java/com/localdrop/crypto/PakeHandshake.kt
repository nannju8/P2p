package com.localdrop.crypto

import org.bouncycastle.crypto.CryptoException
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import org.bouncycastle.crypto.digests.SHA256Digest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Échange PAKE balancé basé sur le code à 4 chiffres.
 *
 * CHOIX D'ARCHITECTURE : BouncyCastle (bcprov) n'embarque pas SPAKE2. Plutôt que d'écrire
 * SPAKE2 « à la main » (interdit par la règle n°4), on utilise **J-PAKE** (RFC 8236) dans
 * l'implémentation éprouvée de BouncyCastle (groupe NIST 2048 bits, SHA-256) :
 *   - le code n'est JAMAIS transmis, ni sous forme de hash : seuls des éléments de groupe
 *     et des preuves à divulgation nulle de connaissance circulent ;
 *   - un attaquant actif ne peut tester qu'UN seul code par session de handshake
 *     (d'où la limite de 3 essais + rotation du code, cf. PairingGuard) ;
 *   - le round 3 fournit la confirmation de clé mutuelle (explicit key confirmation).
 *
 * Séquence (symétrique) : round1 ⇄ round2 ⇄ round3 ; puis [deriveSessionKeys].
 */
class PakeHandshake(
    private val selfId: String,
    private val peerId: String,
    code: CharArray,
    random: SecureRandom = SecureRandom(),
) {
    private val participant = JPAKEParticipant(
        selfId, code, JPAKEPrimeOrderGroups.NIST_2048, SHA256Digest(), random
    )
    private var keyingMaterial: BigInteger? = null

    fun round1(): ByteArray = encode { out ->
        val p = participant.createRound1PayloadToSend()
        out.writeUTF(p.participantId)
        writeBig(out, p.gx1); writeBig(out, p.gx2)
        writeBigs(out, p.knowledgeProofForX1); writeBigs(out, p.knowledgeProofForX2)
    }

    fun onRound1(bytes: ByteArray) = decode(bytes) { inp ->
        val id = inp.readUTF()
        val payload = JPAKERound1Payload(id, readBig(inp), readBig(inp), readBigs(inp), readBigs(inp))
        requirePeer(id)
        participant.validateRound1PayloadReceived(payload)
    }

    fun round2(): ByteArray = encode { out ->
        val p = participant.createRound2PayloadToSend()
        out.writeUTF(p.participantId)
        writeBig(out, p.a); writeBigs(out, p.knowledgeProofForX2s)
    }

    fun onRound2(bytes: ByteArray) = decode(bytes) { inp ->
        val id = inp.readUTF()
        val payload = JPAKERound2Payload(id, readBig(inp), readBigs(inp))
        requirePeer(id)
        participant.validateRound2PayloadReceived(payload)
        keyingMaterial = participant.calculateKeyingMaterial()
    }

    fun round3(): ByteArray = encode { out ->
        val p = participant.createRound3PayloadToSend(checkNotNull(keyingMaterial) { "round2 manquant" })
        out.writeUTF(p.participantId)
        writeBig(out, p.magTag)
    }

    /** @throws CryptoException si le code saisi diffère (confirmation de clé échouée). */
    fun onRound3(bytes: ByteArray) = decode(bytes) { inp ->
        val id = inp.readUTF()
        val payload = JPAKERound3Payload(id, readBig(inp))
        requirePeer(id)
        participant.validateRound3PayloadReceived(payload, checkNotNull(keyingMaterial))
    }

    /** À appeler uniquement après round3 validé des deux côtés. */
    fun deriveSessionKeys(): SessionKeys {
        val km = checkNotNull(keyingMaterial)
        val ikm = km.toByteArray()
        try {
            val ids = listOf(selfId, peerId).sorted().joinToString("|")
            val salt = "LocalDrop/v1/$ids".toByteArray(Charsets.UTF_8)
            val sessionKey = Hkdf.derive(ikm, salt, "session-key".toByteArray(), 32)
            val confirmKey = Hkdf.derive(ikm, salt, "confirm-key".toByteArray(), 32)
            return SessionKeys(sessionKey, confirmKey)
        } finally {
            java.util.Arrays.fill(ikm, 0)
            keyingMaterial = null
        }
    }

    private fun requirePeer(id: String) {
        if (id != peerId) throw CryptoException("Identité de pair inattendue")
    }

    // ── sérialisation binaire minimale (longueur préfixée, taille bornée) ───────────────
    private fun encode(block: (DataOutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use(block)
        return bos.toByteArray()
    }
    private fun <T> decode(bytes: ByteArray, block: (DataInputStream) -> T): T =
        DataInputStream(ByteArrayInputStream(bytes)).use(block)

    private fun writeBig(out: DataOutputStream, v: BigInteger) {
        val b = v.toByteArray(); out.writeShort(b.size); out.write(b)
    }
    private fun readBig(inp: DataInputStream): BigInteger {
        val n = inp.readUnsignedShort()
        if (n == 0 || n > 600) throw CryptoException("Entier hors limites")
        return BigInteger(ByteArray(n).also(inp::readFully))
    }
    private fun writeBigs(out: DataOutputStream, a: Array<BigInteger>) {
        out.writeByte(a.size); a.forEach { writeBig(out, it) }
    }
    private fun readBigs(inp: DataInputStream): Array<BigInteger> {
        val n = inp.readUnsignedByte()
        if (n > 4) throw CryptoException("Trop de preuves")
        return Array(n) { readBig(inp) }
    }
}
