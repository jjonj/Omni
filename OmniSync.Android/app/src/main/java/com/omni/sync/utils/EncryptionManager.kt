package com.omni.sync.utils

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.*
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptionManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("encryption_prefs", Context.MODE_PRIVATE)
    private val RSA_KEY_SIZE = 2048
    private val AES_KEY_SIZE = 256

    fun hasKeys(): Boolean {
        return prefs.contains("public_key") && prefs.contains("encrypted_private_key")
    }

    fun generateKeys(password: String) {
        Log.d("EncryptionManager", "Generating new RSA KeyPair")
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(RSA_KEY_SIZE)
        val kp = kpg.generateKeyPair()

        val publicKey = kp.public
        val privateKey = kp.private

        // Store Public Key in plain text (Base64)
        val pubBase64 = Base64.encodeToString(publicKey.encoded, Base64.DEFAULT)
        prefs.edit().putString("public_key", pubBase64).apply()

        // Encrypt Private Key with password
        encryptAndStorePrivateKey(privateKey, password)
    }

    internal fun encryptAndStorePrivateKey(privateKey: PrivateKey, password: String) {
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val passwordKey = deriveKeyFromPassword(password, salt)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, passwordKey)
        val iv = cipher.iv
        val encryptedKey = cipher.doFinal(privateKey.encoded)

        prefs.edit().apply {
            putString("encrypted_private_key", Base64.encodeToString(encryptedKey, Base64.DEFAULT))
            putString("private_key_iv", Base64.encodeToString(iv, Base64.DEFAULT))
            putString("private_key_salt", Base64.encodeToString(salt, Base64.DEFAULT))
            apply()
        }
    }

    fun getPrivateKey(password: String): PrivateKey? {
        val encryptedKeyBase64 = prefs.getString("encrypted_private_key", null) ?: return null
        val ivBase64 = prefs.getString("private_key_iv", null) ?: return null
        val saltBase64 = prefs.getString("private_key_salt", null) ?: return null

        val encryptedKey = Base64.decode(encryptedKeyBase64, Base64.DEFAULT)
        val iv = Base64.decode(ivBase64, Base64.DEFAULT)
        val salt = Base64.decode(saltBase64, Base64.DEFAULT)

        val passwordKey = deriveKeyFromPassword(password, salt)
        
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, passwordKey, GCMParameterSpec(128, iv))
            val decryptedKeyBytes = cipher.doFinal(encryptedKey)
            
            val kf = KeyFactory.getInstance("RSA")
            kf.generatePrivate(PKCS8EncodedKeySpec(decryptedKeyBytes))
        } catch (e: Exception) {
            Log.e("EncryptionManager", "Failed to decrypt private key: ${e.message}")
            null
        }
    }

    fun getPublicKey(): PublicKey? {
        val pubBase64 = prefs.getString("public_key", null) ?: return null
        val pubBytes = Base64.decode(pubBase64, Base64.DEFAULT)
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePublic(X509EncodedKeySpec(pubBytes))
    }

    private fun deriveKeyFromPassword(password: String, salt: ByteArray): SecretKey {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val keyBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    // --- File Encryption Helpers ---

    fun generateRandomAesKey(): SecretKey {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(AES_KEY_SIZE)
        return kg.generateKey()
    }

    fun encryptAesKeyWithRsa(aesKey: SecretKey, publicKey: PublicKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(aesKey.encoded)
    }

    fun decryptAesKeyWithRsa(encryptedAesKey: ByteArray, privateKey: PrivateKey): SecretKey {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val aesKeyBytes = cipher.doFinal(encryptedAesKey)
        return SecretKeySpec(aesKeyBytes, "AES")
    }
}