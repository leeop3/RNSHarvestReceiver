package com.harvest.rns.data.model

/**
 * Represents a remote RNS node discovered via an ANNOUNCE packet.
 *
 * RNS ANNOUNCE packet data layout (after the RNS header):
 *   [0..31]  Public key (Ed25519, 32 bytes)
 *   [32..63] App data public key (X25519, 32 bytes)
 *   [64..]   App data (arbitrary bytes — LXMF nodes put display name here)
 *   [last 64 bytes] Ed25519 signature over all preceding bytes
 *
 * The destination hash (10 bytes, truncated SHA-256) comes from the RNS
 * packet header and uniquely identifies this node on the network.
 */
data class DiscoveredNode(
    /** 10-byte destination hash as hex string (20 hex chars) — the node's RNS address */
    val destinationHash: String,

    /** Human-readable display name extracted from ANNOUNCE app data, or null */
    val displayName: String?,

    /** Aspect string from app data (e.g. "lxmf.delivery", "harvest.reporter") */
    val aspect: String?,

    /** Number of hops this ANNOUNCE travelled — 0 = directly connected */
    val hops: Int,

    /** Epoch millis when this announce was first received */
    val firstSeen: Long = System.currentTimeMillis(),

    /** Epoch millis when this announce was last received */
    val lastSeen: Long = System.currentTimeMillis(),

    /** How many times this node has announced */
    val announceCount: Int = 1
) {
    /** Friendly label: display name if available, otherwise truncated hash */
    val label: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: "Node ${destinationHash.take(8)}…"

    /** Last 4 bytes of hash as short address label */
    val shortAddress: String
        get() = destinationHash.takeLast(8).chunked(2).joinToString(":")
}
