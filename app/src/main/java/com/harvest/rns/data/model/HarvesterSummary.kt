package com.harvest.rns.data.model

/**
 * Aggregated summary per harvester for a given date.
 * Produced by a Room query — not a stored entity.
 */
data class HarvesterSummary(
    val harvesterId: String,
    val reportDate: String,
    val totalRipeBunches: Int,
    val totalEmptyBunches: Int,
    val reportCount: Int,
    val totalBunches: Int = totalRipeBunches + totalEmptyBunches
)
