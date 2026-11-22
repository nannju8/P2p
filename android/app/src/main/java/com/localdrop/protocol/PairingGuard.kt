package com.localdrop.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

/**
 * Côté ENVOYEUR uniquement. Génère le code à 4 chiffres (SecureRandom), applique :
 *  - expiration (5 min par défaut) ;
 *  - 3 échecs consécutifs ⇒ verrouillage, cooldown 60 s pour le pair fautif (IP/appareil)
 *    ET rotation automatique du code.
 * Le code n'est jamais journalisé ni transmis : il alimente uniquement PakeHandshake.
 */
class PairingGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    val maxAttempts: Int = 3,
    val cooldownMs: Long = 60_000,
    val codeTtlMs: Long = 5 * 60_000,
) {
    sealed interface Failure {
        data class Retry(val remaining: Int) : Failure
        data class Locked(val cooldownMs: Long) : Failure
    }

    private val lock = Any()
    private var code = newCode()
    private var issuedAt = clock()
    private var failures = 0
    private val blockedUntil = HashMap<String, Long>()

    private val _display = MutableStateFlow(code)
    /** Code affiché en grand sur l'écran de l'envoyeur. */
    val displayedCode: StateFlow<String> = _display

    private fun newCode(): String = "%04d".format(random.nextInt(10_000))

    /** Bouton « Nouveau code » + rotation automatique. */
    fun rotate(): String = synchronized(lock) {
        code = newCode(); issuedAt = clock(); failures = 0
        _display.value = code
        code
    }

    fun remainingMs(): Long = synchronized(lock) { (issuedAt + codeTtlMs - clock()).coerceAtLeast(0) }

    /** Code à fournir à PAKE, ou null s'il a expiré (la session doit alors être refusée). */
    fun codeForHandshake(): CharArray? = synchronized(lock) {
        if (remainingMs() == 0L) null else code.toCharArray()
    }

    fun blockedFor(peer: String): Long = synchronized(lock) {
        val until = blockedUntil[peer] ?: return 0
        (until - clock()).coerceAtLeast(0)
    }

    fun recordFailure(peer: String): Failure = synchronized(lock) {
        failures++
        if (failures >= maxAttempts) {
            blockedUntil[peer] = clock() + cooldownMs
            rotate()
            Failure.Locked(cooldownMs)
        } else Failure.Retry(maxAttempts - failures)
    }

    fun recordSuccess(peer: String) = synchronized(lock) { failures = 0; blockedUntil.remove(peer); Unit }
}
