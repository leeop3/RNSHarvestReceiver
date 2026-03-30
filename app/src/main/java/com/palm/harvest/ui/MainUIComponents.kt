package com.palm.harvest.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.palm.harvest.R
import com.palm.harvest.data.HarvestReport

// 1. THE VIEW PAGER ADAPTER
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 2
    override fun createFragment(position: Int): Fragment {
        return if (position == 0) IncomingFragment() else SummaryFragment()
    }
}

// 2. INCOMING DATA FRAGMENT
class IncomingFragment : Fragment(R.layout.fragment_incoming) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.recyclerViewIncoming)
        rv?.layoutManager = LinearLayoutManager(context)
    }
}

// 3. SUMMARY FRAGMENT
class SummaryFragment : Fragment(R.layout.fragment_summary) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.recyclerViewSummary)
        rv?.layoutManager = LinearLayoutManager(context)
    }
}