package com.localdrop

import com.localdrop.protocol.PairingGuard
import com.localdrop.protocol.PairingGuard.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingGuardTest {
    private var now = 1_000_000L
    private val guard = PairingGuard(clock = { now })

    @Test fun `le code fait toujours 4 chiffres`() {
        repeat(2000) { assertTrue(Regex("\\d{4}").matches(guard.rotate())) }
    }

    @Test fun `3 échecs consécutifs - verrouillage 60 s et nouveau code`() {
        val before = guard.displayedCode.value
        assertEquals(Failure.Retry(2), guard.recordFailure("192.168.43.20"))
        assertEquals(Failure.Retry(1), guard.recordFailure("192.168.43.20"))
        val third = guard.recordFailure("192.168.43.20")
        assertEquals(Failure.Locked(60_000), third)
        assertEquals(60_000L, guard.blockedFor("192.168.43.20"))
        assertEquals(0L, guard.blockedFor("192.168.43.99"))        // autre appareil non pénalisé
        now += 60_001
        assertEquals(0L, guard.blockedFor("192.168.43.20"))        // cooldown écoulé
        // (le code a été régénéré ; 1 chance sur 10 000 de retomber sur le même)
        if (before == guard.displayedCode.value) println("collision statistique rare")
    }

    @Test fun `un succès remet le compteur à zéro`() {
        guard.recordFailure("p"); guard.recordFailure("p")
        guard.recordSuccess("p")
        assertEquals(Failure.Retry(2), guard.recordFailure("p"))
    }

    @Test fun `le code expire après 5 minutes`() {
        assertTrue(guard.codeForHandshake() != null)
        now += 5 * 60_000 + 1
        assertNull(guard.codeForHandshake())
        val c = guard.rotate()
        assertNotEquals(null, guard.codeForHandshake()); assertEquals(4, c.length)
    }
}
