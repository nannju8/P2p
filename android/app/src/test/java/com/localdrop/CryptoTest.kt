package com.localdrop

import com.localdrop.crypto.PakeHandshake
import com.localdrop.crypto.SecureChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.SecureRandom

class CryptoTest {

    private fun runPake(codeA: String, codeB: String): Pair<PakeHandshake, PakeHandshake> {
        val s = PakeHandshake("sender", "receiver", codeA.toCharArray())
        val r = PakeHandshake("receiver", "sender", codeB.toCharArray())
        val s1 = s.round1(); val r1 = r.round1()
        s.onRound1(r1); r.onRound1(s1)
        val s2 = s.round2(); val r2 = r.round2()
        s.onRound2(r2); r.onRound2(s2)
        return s to r
    }

    @Test fun `bon code - les deux côtés dérivent exactement les mêmes clés`() {
        val (s, r) = runPake("4827", "4827")
        val s3 = s.round3(); val r3 = r.round3()
        s.onRound3(r3); r.onRound3(s3)                     // confirmation mutuelle OK
        val ks = s.deriveSessionKeys().channelKeys(byteArrayOf(1))
        val kr = r.deriveSessionKeys().channelKeys(byteArrayOf(1))
        assertArrayEquals(ks.senderToReceiver, kr.senderToReceiver)
        assertArrayEquals(ks.receiverToSender, kr.receiverToSender)
        assertFalse(ks.senderToReceiver.contentEquals(ks.receiverToSender))
    }

    @Test fun `mauvais code - la confirmation de clé échoue et aucune clé n'est dérivée`() {
        val (s, r) = runPake("4827", "4828")
        val s3 = s.round3(); val r3 = r.round3()
        try { s.onRound3(r3); fail("doit échouer") } catch (_: Exception) {}
        try { r.onRound3(s3); fail("doit échouer") } catch (_: Exception) {}
    }

    @Test fun `le code n'apparaît jamais dans les messages réseau`() {
        val s = PakeHandshake("sender", "receiver", "4827".toCharArray())
        val bytes = s.round1()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("4827"))
    }

    private fun pipePair(): Pair<SecureChannel, SecureChannel> {
        val aToB = PipedOutputStream(); val bIn = PipedInputStream(aToB, 4 * 1024 * 1024)
        val bToA = PipedOutputStream(); val aIn = PipedInputStream(bToA, 4 * 1024 * 1024)
        val k1 = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val k2 = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return SecureChannel(aIn, aToB, k1, k2, SecureChannel.SENDER_PREFIX, SecureChannel.RECEIVER_PREFIX) to
            SecureChannel(bIn, bToA, k2, k1, SecureChannel.RECEIVER_PREFIX, SecureChannel.SENDER_PREFIX)
    }

    @Test fun `AES-GCM - aller-retour d'une trame de 512 Ko bit à bit`() {
        val (a, b) = pipePair()
        val data = ByteArray(512 * 1024).also { SecureRandom().nextBytes(it) }
        a.send(4, data)
        val f = b.receive()
        assertEquals(4.toByte(), f.type)
        assertArrayEquals(data, f.payload)
    }

    @Test fun `AES-GCM - une trame altérée est rejetée`() {
        val key = ByteArray(32) { 7 }
        val wire = ByteArrayOutputStream()
        val tx = SecureChannel(ByteArrayInputStream(ByteArray(0)), wire, key, key, 1, 2)
        tx.send(1, "secret".toByteArray())
        val bytes = wire.toByteArray(); bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 1).toByte()
        val rx = SecureChannel(ByteArrayInputStream(bytes), ByteArrayOutputStream(), key, key, 2, 1)
        try { rx.receive(); fail("doit être rejetée") } catch (_: IOException) {}
    }

    @Test fun `AES-GCM - le rejeu d'une trame est rejeté (nonce incrémental)`() {
        val key = ByteArray(32) { 9 }
        val wire = ByteArrayOutputStream()
        val tx = SecureChannel(ByteArrayInputStream(ByteArray(0)), wire, key, key, 1, 2)
        tx.send(1, "pay 10".toByteArray())
        val one = wire.toByteArray()
        val replay = one + one                              // même trame envoyée deux fois
        val rx = SecureChannel(ByteArrayInputStream(replay), ByteArrayOutputStream(), key, key, 2, 1)
        assertTrue(rx.receive().payload.contentEquals("pay 10".toByteArray()))
        try { rx.receive(); fail("rejeu accepté !") } catch (_: IOException) {}
    }
}
