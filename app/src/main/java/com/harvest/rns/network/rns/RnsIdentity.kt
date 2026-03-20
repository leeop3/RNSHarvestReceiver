package com.harvest.rns.network.rns

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RNS Identity — manages Ed25519 signing keypair and X25519 encryption keypair.
 *
 * RNS Identity structure:
 *   Ed25519 keypair  (32-byte pub) — used for signing announces
 *   X25519 keypair   (32-byte pub) — used for ECDH encryption
 *
 * RNS destination hash = SHA-256(ed25519_pub + x25519_pub)[0:16]
 * This is what other nodes use as the "address" to route to us.
 *
 * RNS encryption (for incoming DATA packets to us):
 *   sender generates ephemeral X25519 keypair
 *   shared_secret = ECDH(sender_ephemeral_priv, our_x25519_pub)
 *   key_material  = HKDF(shared_secret)
 *   ciphertext    = AES-256-GCM(key_material, plaintext)
 *   packet        = [sender_ephemeral_pub (32)] + [iv (16)] + [ciphertext]
 *
 * Reference: https://github.com/markqvist/Reticulum/blob/master/RNS/Identity.py
 */
class RnsIdentity private constructor(
    val ed25519Pub:  ByteArray,  // 32 bytes
    val ed25519Priv: ByteArray,  // 32 bytes
    val x25519Pub:   ByteArray,  // 32 bytes
    val x25519Priv:  ByteArray   // 32 bytes
) {
    companion object {
        private const val TAG = "RnsIdentity"
        private const val PREFS_NAME    = "rns_identity"
        private const val PREF_ED_PUB   = "ed_pub"
        private const val PREF_ED_PRIV  = "ed_priv"
        private const val PREF_X_PUB    = "x_pub"
        private const val PREF_X_PRIV   = "x_priv"

        /**
         * Load existing identity from SharedPreferences or generate a new one.
         */
        fun loadOrCreate(context: Context): RnsIdentity {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val edPub  = prefs.getString(PREF_ED_PUB,  null)?.b64decode()
            val edPriv = prefs.getString(PREF_ED_PRIV, null)?.b64decode()
            val xPub   = prefs.getString(PREF_X_PUB,   null)?.b64decode()
            val xPriv  = prefs.getString(PREF_X_PRIV,  null)?.b64decode()

            if (edPub  != null && edPriv != null &&
                xPub   != null && xPriv  != null &&
                edPub.size == 32 && xPub.size == 32) {
                Log.i(TAG, "Loaded existing identity: ${hash(edPub, xPub).toHex()}")
                return RnsIdentity(edPub, edPriv, xPub, xPriv)
            }

            return generate(context)
        }

        private fun generate(context: Context): RnsIdentity {
            val rng = SecureRandom()

            // Generate Ed25519 keypair
            val edGen = Ed25519KeyPairGenerator()
            edGen.init(Ed25519KeyGenerationParameters(rng))
            val edPair  = edGen.generateKeyPair()
            val edPub   = (edPair.public  as Ed25519PublicKeyParameters).encoded
            val edPriv  = (edPair.private as Ed25519PrivateKeyParameters).encoded

            // Generate X25519 keypair
            val xGen = X25519KeyPairGenerator()
            xGen.init(X25519KeyGenerationParameters(rng))
            val xPair   = xGen.generateKeyPair()
            val xPub    = (xPair.public  as X25519PublicKeyParameters).encoded
            val xPriv   = (xPair.private as X25519PrivateKeyParameters).encoded

            val identity = RnsIdentity(edPub, edPriv, xPub, xPriv)
            val address  = identity.addressHex

            // Persist
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_ED_PUB,  edPub.b64encode())
                .putString(PREF_ED_PRIV, edPriv.b64encode())
                .putString(PREF_X_PUB,  xPub.b64encode())
                .putString(PREF_X_PRIV, xPriv.b64encode())
                .apply()

            Log.i(TAG, "Generated new RNS identity: $address")
            return identity
        }

        private fun hash(edPub: ByteArray, xPub: ByteArray): ByteArray {
            val combined = ByteArray(64).also {
                edPub.copyInto(it, 0)
                xPub.copyInto(it, 32)
            }
            return MessageDigest.getInstance("SHA-256").digest(combined).copyOf(16)
        }

        private fun String.b64decode() = Base64.decode(this, Base64.NO_WRAP)
        private fun ByteArray.b64encode() = Base64.encodeToString(this, Base64.NO_WRAP)
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }

    /** 16-byte (32 hex char) RNS destination hash — the address shown to users */
    val addressBytes: ByteArray by lazy {
        val combined = ByteArray(64).also {
            ed25519Pub.copyInto(it, 0)
            x25519Pub.copyInto(it, 32)
        }
        MessageDigest.getInstance("SHA-256").digest(combined).copyOf(16)
    }

    /** 32-character hex address (what users enter in their sender config) */
    val addressHex: String get() = addressBytes.joinToString("") { "%02x".format(it) }

    /** 10-byte truncated hash for RNS wire routing */
    val truncatedHash: ByteArray get() = addressBytes.copyOf(10)

    /**
     * Decrypt an incoming RNS-encrypted DATA packet payload.
     *
     * RNS encryption format for a message TO this identity:
     *   [0..31]  ephemeral_pub  — sender's ephemeral X25519 public key
     *   [32..47] iv             — 16-byte AES-GCM IV / nonce
     *   [48..]   ciphertext     — AES-256-GCM encrypted content + 16-byte tag
     *
     * @return decrypted bytes or null if decryption fails
     */
    fun decrypt(encryptedPayload: ByteArray): ByteArray? {
        if (encryptedPayload.size < 48 + 16) {
            Log.d(TAG, "Payload too short to decrypt: ${encryptedPayload.size}b")
            return null
        }

        return try {
            // Extract ephemeral public key
            val ephemeralPubBytes = encryptedPayload.copyOfRange(0, 32)
            val iv                = encryptedPayload.copyOfRange(32, 48)
            val ciphertext        = encryptedPayload.copyOfRange(48, encryptedPayload.size)

            // ECDH: compute shared secret
            val ephemeralPub = X25519PublicKeyParameters(ephemeralPubBytes, 0)
            val ourPriv      = X25519PrivateKeyParameters(x25519Priv, 0)
            val agreement    = org.bouncycastle.crypto.agreement.X25519Agreement()
            agreement.init(ourPriv)
            val sharedSecret = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(ephemeralPub, sharedSecret, 0)

            // Derive AES key via HKDF-SHA256
            val aesKey = hkdf(sharedSecret, "RNS_IDENTITY_ECDH".toByteArray(), 32)

            // Decrypt with AES-256-GCM
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)

        } catch (e: Exception) {
            Log.d(TAG, "Decryption failed: ${e.message}")
            null
        }
    }

    /**
     * HKDF-SHA256 key derivation (RFC 5869).
     * Used by RNS to derive the AES key from the ECDH shared secret.
     */
    private fun hkdf(ikm: ByteArray, info: ByteArray, outputLen: Int): ByteArray {
        // Extract: PRK = HMAC-SHA256(salt=zeros, IKM)
        val salt = ByteArray(32)
        val prk  = hmacSha256(salt, ikm)

        // Expand: T(1) = HMAC-SHA256(PRK, info || 0x01)
        val expand = hmacSha256(prk, info + byteArrayOf(0x01))
        return expand.copyOf(outputLen)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * Build the announce app data field for LXMF delivery.
     * Format: ed25519_pub (32) + x25519_pub (32) + name_hash (10) + random (10) + app_data
     */
    fun buildAnnounceData(appData: ByteArray): ByteArray {
        val nameHash   = MessageDigest.getInstance("SHA-256")
            .digest(appData).copyOf(10)
        val randomBlob = ByteArray(10).also { SecureRandom().nextBytes(it) }

        val out = ByteArray(32 + 32 + 10 + 10 + appData.size)
        ed25519Pub.copyInto(out, 0)
        x25519Pub.copyInto(out, 32)
        nameHash.copyInto(out, 64)
        randomBlob.copyInto(out, 74)
        appData.copyInto(out, 84)
        return out
    }
}
