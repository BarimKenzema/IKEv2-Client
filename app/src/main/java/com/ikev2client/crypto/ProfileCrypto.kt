package com.ikev2client.crypto

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ikev2client.model.ProfileBundle
import com.ikev2client.model.VpnProfile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts profiles that were encrypted by the HTML generator tool.
 *
 * Format of encrypted blob (Base64-encoded):
 *   [12 bytes IV][N bytes ciphertext+GCM tag]
 *
 * Encryption: AES-256-GCM
 * Key derivation: SHA-256 of passphrase
 *
 * This matches the Web Crypto API used in the HTML tool.
 */
object ProfileCrypto {

    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128 // bits

    /**
     * Derive a 256-bit key from passphrase using SHA-256
     * (matches the HTML generator's key derivation)
     */
    private fun deriveKey(passphrase: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(passphrase.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Decrypt an encrypted profile blob.
     *
     * @param encryptedBase64 The base64-encoded encrypted data from QR or paste
     * @param passphrase The passphrase used during encryption
     * @return List of VpnProfile objects
     * @throws SecurityException if decryption fails or profiles are all expired
     */
    fun decryptProfiles(encryptedBase64: String, passphrase: String): List<VpnProfile> {
        try {
            // Clean input
            val cleaned = encryptedBase64.trim().replace("\\s".toRegex(), "")

            // Decode base64
            val encryptedBytes = Base64.decode(cleaned, Base64.DEFAULT)

            if (encryptedBytes.size < GCM_IV_LENGTH + 1) {
                throw SecurityException("Invalid encrypted data: too short")
            }

            // Extract IV (first 12 bytes)
            val iv = encryptedBytes.copyOfRange(0, GCM_IV_LENGTH)

            // Extract ciphertext + tag (remaining bytes)
            val ciphertext = encryptedBytes.copyOfRange(GCM_IV_LENGTH, encryptedBytes.size)

            // Derive key
            val key = deriveKey(passphrase)

            // Decrypt
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            val decryptedBytes = cipher.doFinal(ciphertext)
            val json = String(decryptedBytes, StandardCharsets.UTF_8)

            // Parse JSON
            val gson = Gson()
            return try {
                // Try parsing as ProfileBundle first ({"profiles": [...]})
                val bundle = gson.fromJson(json, ProfileBundle::class.java)
                bundle.profiles
            } catch (e: Exception) {
                try {
                    // Try parsing as direct array
                    val type = object : TypeToken<List<VpnProfile>>() {}.type
                    gson.fromJson<List<VpnProfile>>(json, type)
                } catch (e2: Exception) {
                    // Try parsing as single profile
                    val single = gson.fromJson(json, VpnProfile::class.java)
                    listOf(single)
                }
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            throw SecurityException("Decryption failed. Wrong passphrase or corrupted data.", e)
        }
    }

    /**
     * Validate that at least one profile is not expired
     */
    fun filterValidProfiles(profiles: List<VpnProfile>): Pair<List<VpnProfile>, List<VpnProfile>> {
        val valid = profiles.filter { !it.isExpired() }
        val expired = profiles.filter { it.isExpired() }
        return Pair(valid, expired)
    }
}
