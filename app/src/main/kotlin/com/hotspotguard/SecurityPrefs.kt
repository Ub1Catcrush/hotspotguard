package com.tvcs.hotspotguard

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Verschlüsselte SharedPreferences ohne deprecated AndroidX-Security-Bibliothek.
 *
 * Verwendet Android Keystore (AES-256-GCM) direkt via javax.crypto.
 * Der Schlüssel wird im Hardware-backed Keystore des Geräts gespeichert
 * und verlässt diesen nie – sicherer als EncryptedSharedPreferences.
 *
 * Werte-Format im Storage: Base64(IV [12 Byte] + Ciphertext)
 */
class SecurityPrefs(private val context: Context) {

    companion object {
        private const val PREFS_NAME        = "hsg_prefs"
        private const val KEYSTORE_ALIAS    = "HotspotGuardKey"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val CIPHER_ALGO       = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH    = 128
        private const val IV_LENGTH         = 12

        const val KEY_PASSWORD_HASH    = "ph"
        const val KEY_PASSWORD_SET     = "ps"
        const val KEY_FAILED_ATTEMPTS  = "fa"
        const val KEY_BLOCKED_COUNT    = "bc"
        const val KEY_VPN_PROTECTION   = "vp"
        const val KEY_GUARD_ENABLED    = "ge"
    }

    // Rohe (unverschlüsselte) SharedPreferences – Werte werden manuell ver-/entschlüsselt
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── Keystore ────────────────────────────────────────────────────────────

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.getKey(KEYSTORE_ALIAS, null)?.let { return it as SecretKey }

        // Schlüssel existiert noch nicht – neu generieren
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return keyGen.generateKey()
    }

    // ─── Ver-/Entschlüsselung ────────────────────────────────────────────────

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(CIPHER_ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv         = cipher.iv          // GCM generiert automatisch 12-Byte-IV
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined   = iv + ciphertext    // IV voranhängen
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? {
        return try {
            val combined   = Base64.decode(encoded, Base64.NO_WRAP)
            val iv         = combined.copyOfRange(0, IV_LENGTH)
            val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher     = Cipher.getInstance(CIPHER_ALGO)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null  // Schlüssel gewechselt oder Daten korrupt – liefert null
        }
    }

    // ─── Typed Accessors ─────────────────────────────────────────────────────

    private fun putString(key: String, value: String) =
        prefs.edit().putString(key, encrypt(value)).apply()

    private fun getString(key: String, default: String? = null): String? =
        prefs.getString(key, null)?.let { decrypt(it) } ?: default

    private fun putBoolean(key: String, value: Boolean) =
        putString(key, value.toString())

    private fun getBoolean(key: String, default: Boolean): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: default

    private fun putInt(key: String, value: Int) =
        putString(key, value.toString())

    private fun getInt(key: String, default: Int): Int =
        getString(key)?.toIntOrNull() ?: default

    // ─── Passwort ────────────────────────────────────────────────────────────

    fun setPassword(password: String) {
        putString(KEY_PASSWORD_HASH, hashPassword(password))
        putBoolean(KEY_PASSWORD_SET, true)
    }

    fun verifyPassword(password: String): Boolean {
        val stored = getString(KEY_PASSWORD_HASH) ?: return false
        return hashPassword(password) == stored
    }

    fun isPasswordSet(): Boolean = getBoolean(KEY_PASSWORD_SET, false)

    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ─── Fehlversuche ────────────────────────────────────────────────────────

    fun incrementFailedAttempts(): Int {
        val next = getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        putInt(KEY_FAILED_ATTEMPTS, next)
        return next
    }

    fun resetFailedAttempts() = putInt(KEY_FAILED_ATTEMPTS, 0)

    fun getFailedAttempts(): Int = getInt(KEY_FAILED_ATTEMPTS, 0)

    // ─── Blockierungszähler ──────────────────────────────────────────────────

    fun incrementBlockedCount(): Int {
        val next = getInt(KEY_BLOCKED_COUNT, 0) + 1
        putInt(KEY_BLOCKED_COUNT, next)
        return next
    }

    fun getBlockedCount(): Int = getInt(KEY_BLOCKED_COUNT, 0)

    fun resetBlockedCount()     = putInt(KEY_BLOCKED_COUNT, 0)

    // ─── VPN Schutz ──────────────────────────────────────────────────────────

    fun isVpnProtectionEnabled(): Boolean  = getBoolean(KEY_VPN_PROTECTION, false)
    fun setVpnProtection(enabled: Boolean) = putBoolean(KEY_VPN_PROTECTION, enabled)

    // ─── Guard Enabled ───────────────────────────────────────────────────────

    fun isGuardEnabled(): Boolean          = getBoolean(KEY_GUARD_ENABLED, true)
    fun setGuardEnabled(enabled: Boolean)  = putBoolean(KEY_GUARD_ENABLED, enabled)

    // ─── Reset ───────────────────────────────────────────────────────────────

    /** Löscht alle gespeicherten Daten – für Factory Reset */
    fun clearAll() {
        prefs.edit().clear().apply()
        // Keystore-Schlüssel ebenfalls löschen damit kein Entschlüsseln mehr möglich
        try {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
                load(null)
                deleteEntry(KEYSTORE_ALIAS)
            }
        } catch (_: Exception) {}
    }
}
