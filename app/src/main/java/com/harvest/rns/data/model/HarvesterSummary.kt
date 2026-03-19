package com.harvest.rns.data.model

import androidx.room.Ignore

/**
 * Aggregated summary per harvester for a given date.
 * Produced by a Room query — not a stored entity.
 *
 * Room maps harvesterId, reportDate, totalRipeBunches, totalEmptyBunches,
 * reportCount directly from the SQL result columns.
 * totalBunches is marked @Ignore so Room's KAPT processor skips it;
 * it is computed in the secondary constructor after Room fills the primary fields.
 */
data class HarvesterSummary(
    val harvesterId: String,
    val reportDate: String,
    val totalRipeBunches: Int,
    val totalEmptyBunches: Int,
    val reportCount: Int
) {
    @Ignore
    val totalBunches: Int = totalRipeBunches + totalEmptyBunches
}
