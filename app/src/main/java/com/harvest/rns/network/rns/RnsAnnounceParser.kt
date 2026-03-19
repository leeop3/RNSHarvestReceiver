package com.harvest.rns.network.rns

import android.util.Log
import com.harvest.rns.data.model.DiscoveredNode

/**
 * Parses RNS ANNOUNCE packets to extract node identity information.
 *
 * RNS ANNOUNCE packet data (after the RNS header strips destination hash):
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  Public Key        [32 bytes]  Ed25519 identity public key      │
 * │  App Data PubKey   [32 bytes]  X25519 key for encryption        │
 * │  Name Hash         [10 bytes]  SHA-256 truncated hash of name   │
 * │  Random Blob       [10 bytes]  Anti-replay random bytes         │
 * │  App Data          [variable]  UTF-8 encoded aspect + name      │
 * │  Signature         [64 bytes]  Ed25519 signature                │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * App data format (LXMF nodes):
 *   The app data typically starts with the aspect string (e.g. "lxmf.delivery")
 *   followed by an optional display name after a null byte or separator.
 *
 * Reference: https://reticulum.network/manual/understanding.html#announces
 */
object RnsAnnounceParser {

    private const val TAG = "RnsAnnounceParser"

    private const val ED25519_KEY_SIZE  = 32
    private const val X25519_KEY_SIZE   = 32
    private const val NAME_HASH_SIZE    = 10
    private const val RANDOM_BLOB_SIZE  = 10
    private const val SIGNATURE_SIZE    = 64
    private const val FIXED_HEADER_SIZE = ED25519_KEY_SIZE + X25519_KEY_SIZE +
                                          NAME_HASH_SIZE + RANDOM_BLOB_SIZE
    private const val MIN_ANNOUNCE_SIZE = FIXED_HEADER_SIZE + SIGNATURE_SIZE

    /**
     * Parse an RNS ANNOUNCE packet data payload.
     *
     * @param destinationHash  The 10-byte dest hash from the RNS packet header (as hex)
     * @param announceData     The raw data bytes from the RNS packet body
     * @param hops             Hop count from the RNS packet header
     * @return A [DiscoveredNode] if parsing succeeds, null otherwise
     */
    fun parse(
        destinationHash: String,
        announceData: ByteArray,
        hops: Int
    ): DiscoveredNode? {
        if (announceData.size < MIN_ANNOUNCE_SIZE) {
            Log.d(TAG, "Announce too small: ${announceData.size} < $MIN_ANNOUNCE_SIZE")
            return null
        }

        return try {
            // Skip fixed header fields (keys + name hash + random blob)
            val appDataStart = FIXED_HEADER_SIZE
            val appDataEnd   = announceData.size - SIGNATURE_SIZE

            val (aspect, displayName) = if (appDataEnd > appDataStart) {
                val appData = announceData.copyOfRange(appDataStart, appDataEnd)
                parseAppData(appData)
            } else {
                Pair(null, null)
            }

            Log.d(TAG, "Announce from $destinationHash: aspect=$aspect name=$displayName hops=$hops")

            DiscoveredNode(
                destinationHash = destinationHash,
                displayName     = displayName,
                aspect          = aspect,
                hops            = hops
            )
        } catch (e: Exception) {
            Log.e(TAG, "Announce parse error: ${e.message}")
            // Still return a basic node entry even if app data parsing fails
            DiscoveredNode(
                destinationHash = destinationHash,
                displayName     = null,
                aspect          = null,
                hops            = hops
            )
        }
    }

    /**
     * Parse the variable-length app data field.
     *
     * RNS app data for named destinations is typically encoded as:
     *   "<aspect>\x00<display_name>"   (null-separated)
     * or just:
     *   "<aspect>"                      (no display name)
     *
     * LXMF delivery nodes use aspect "lxmf.delivery".
     * Custom apps define their own aspects.
     */
    private fun parseAppData(data: ByteArray): Pair<String?, String?> {
        val text = try {
            String(data, Charsets.UTF_8).trim()
        } catch (_: Exception) {
            return Pair(null, null)
        }

        // Try null-byte separation first
        val nullIdx = text.indexOf('\u0000')
        return if (nullIdx >= 0) {
            val aspect = text.substring(0, nullIdx).trim().takeIf { it.isNotBlank() }
            val name   = text.substring(nullIdx + 1).trim().takeIf { it.isNotBlank() }
            Pair(aspect, name)
        } else {
            // Whole thing is the aspect/name
            val clean = text.takeIf { it.isNotBlank() }
            // If it looks like an aspect (contains dot), treat as aspect only
            if (clean?.contains('.') == true) {
                Pair(clean, null)
            } else {
                Pair(null, clean)
            }
        }
    }
}
