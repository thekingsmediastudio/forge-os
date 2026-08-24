package com.forge.os.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.forge.os.domain.security.SecureKeyStore
import timber.log.Timber
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Service responsible for desktop pairing operations.
 * Generates 6-digit pairing codes with 5-minute expiration, and issues
 * signed HS256 JWTs as connection tokens (Task 13).
 */
@Singleton
class PairingService @Inject constructor(
    private val keyStore: SecureKeyStore
) {

    companion object {
        private const val CODE_EXPIRATION_MS = 5 * 60 * 1000L // 5 minutes
        private const val TOKEN_TTL_MS = 365L * 24 * 60 * 60 * 1000 // 1 year
        private const val JWT_SECRET_KEY = "forge_jwt_secret"
        private const val TOKEN_KEY_PREFIX = "desktop_token_"
        private val PERMISSIONS = arrayOf("tools", "sync", "clipboard", "notifications", "config")
    }

    private data class PairingCode(
        val code: String,
        val desktopName: String,
        val expiresAt: Long
    )

    // Store active pairing codes with their metadata
    private val activeCodes = ConcurrentHashMap<String, PairingCode>()

    /**
     * Generate a new 6-digit pairing code for a desktop client.
     *
     * @return Pair of (pairing code, expires_in seconds)
     */
    fun generatePairingCode(desktopName: String): Pair<String, Int> {
        cleanupExpiredCodes()

        val code = generateUniqueCode()
        val expiresAt = System.currentTimeMillis() + CODE_EXPIRATION_MS

        activeCodes[code] = PairingCode(
            code = code,
            desktopName = desktopName,
            expiresAt = expiresAt
        )

        Timber.i("PairingService: Generated code $code for $desktopName, expires at $expiresAt")
        return Pair(code, (CODE_EXPIRATION_MS / 1000).toInt())
    }

    /**
     * Validate a pairing code and return the desktop name if valid.
     * Removes the code after successful validation (single-use).
     */
    fun validateAndConsumePairingCode(code: String): String? {
        val pairingCode = activeCodes[code] ?: return null

        if (System.currentTimeMillis() > pairingCode.expiresAt) {
            activeCodes.remove(code)
            Timber.w("PairingService: Code $code has expired")
            return null
        }

        activeCodes.remove(code)
        Timber.i("PairingService: Code $code validated for ${pairingCode.desktopName}")
        return pairingCode.desktopName
    }

    // ─── JWT token issuance (Task 13.2) ────────────────────────────────────

    /**
     * Issue an HS256-signed JWT for a paired desktop client.
     * Claims: iss=forge-os, sub=desktopId, device_id, permissions, iat, exp (1y).
     */
    fun issueToken(desktopId: String, deviceId: String): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withIssuer("forge-os")
            .withSubject(desktopId)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + TOKEN_TTL_MS))
            .withClaim("device_id", deviceId)
            .withArrayClaim("permissions", PERMISSIONS)
            .sign(Algorithm.HMAC256(jwtSecret()))
    }

    /** Returns true when the token is a valid, unexpired Forge OS JWT. */
    fun isValidToken(token: String): Boolean = try {
        JWT.require(Algorithm.HMAC256(jwtSecret()))
            .withIssuer("forge-os")
            .build()
            .verify(token)
        true
    } catch (e: Exception) {
        false
    }

    // ─── Token persistence (Task 13.3) ─────────────────────────────────────

    fun getToken(desktopId: String): String? = keyStore.getCustomKey("$TOKEN_KEY_PREFIX$desktopId")

    fun saveToken(desktopId: String, token: String) {
        keyStore.saveCustomKey("$TOKEN_KEY_PREFIX$desktopId", token)
    }

    fun revokeToken(desktopId: String) {
        if (keyStore.hasCustomKey("$TOKEN_KEY_PREFIX$desktopId")) {
            keyStore.deleteCustomKey("$TOKEN_KEY_PREFIX$desktopId")
        }
    }

    private fun jwtSecret(): String {
        keyStore.getCustomKey(JWT_SECRET_KEY)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val secret = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")
        keyStore.saveCustomKey(JWT_SECRET_KEY, secret)
        return secret
    }

    // ─── Pairing code helpers ──────────────────────────────────────────────

    private fun generateUniqueCode(): String {
        var code: String
        var attempts = 0
        do {
            code = Random.nextInt(100000, 1000000).toString()
            attempts++
            if (attempts > 100) {
                cleanupExpiredCodes()
                attempts = 0
            }
        } while (activeCodes.containsKey(code))
        return code
    }

    private fun cleanupExpiredCodes() {
        val now = System.currentTimeMillis()
        val expiredCodes = activeCodes.filter { (_, pairingCode) ->
            now > pairingCode.expiresAt
        }.keys

        expiredCodes.forEach { code ->
            activeCodes.remove(code)
            Timber.d("PairingService: Removed expired code $code")
        }
    }

    fun getActivePairingCodeCount(): Int = activeCodes.size
}