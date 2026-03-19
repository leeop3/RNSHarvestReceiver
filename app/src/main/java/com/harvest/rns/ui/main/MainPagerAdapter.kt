package com.harvest.rns.ui.main

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.harvest.rns.ui.incoming.IncomingDataFragment
import com.harvest.rns.ui.nodes.NodesFragment
import com.harvest.rns.ui.summary.HarvesterSummaryFragment

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount() = 3
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> IncomingDataFragment()
        1 -> HarvesterSummaryFragment()
        2 -> NodesFragment()
        else -> throw IllegalArgumentException("Invalid tab: $position")
    }
}
