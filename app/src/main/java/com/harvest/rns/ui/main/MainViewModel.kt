package com.harvest.rns.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.harvest.rns.data.db.HarvestDatabase
import com.harvest.rns.data.model.ConnectionStatus
import com.harvest.rns.data.model.HarvestRecord
import com.harvest.rns.data.model.HarvesterSummary
import com.harvest.rns.data.repository.HarvestRepository
import com.harvest.rns.network.RNSReceiverService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = HarvestDatabase.getInstance(application).harvestDao()
    val repository  = HarvestRepository(dao)

    // ─── Selected Date Filter ─────────────────────────────────────────────────

    private val _selectedDate = MutableLiveData(todayDate())
    val selectedDate: LiveData<String> = _selectedDate

    fun setSelectedDate(date: String) {
        _selectedDate.value = date
    }

    // ─── Records (Incoming Data Tab) ──────────────────────────────────────────

    val allRecords: LiveData<List<HarvestRecord>> = repository.allRecords
    val recordCount: LiveData<Int> = repository.recordCount
    val availableDates: LiveData<List<String>> = repository.availableDates

    // ─── Summaries (Harvester Summary Tab) ────────────────────────────────────

    val allSummaries: LiveData<List<HarvesterSummary>> = repository.allSummaries

    fun getSummaryForDate(date: String) = dao.getSummaryByDate(date)

    // ─── Service Stats ────────────────────────────────────────────────────────

    val messageCount: LiveData<Int>    = RNSReceiverService.messageCount.asLiveData()
    val duplicateCount: LiveData<Int>  = RNSReceiverService.duplicateCount.asLiveData()
    val serviceStatus: LiveData<String> = RNSReceiverService.serviceStatus.asLiveData()
    val lastMessageTime: LiveData<Long> = RNSReceiverService.lastMessageTime.asLiveData()

    // ─── Connection Status (from service binding) ──────────────────────────────

    private val _connectionStatus = MutableLiveData<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: LiveData<ConnectionStatus> = _connectionStatus

    fun updateConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.postValue(status)
    }

    // ─── Data Management ──────────────────────────────────────────────────────

    fun clearAllData() {
        viewModelScope.launch {
            repository.deleteAll()
        }
    }

    private fun todayDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date())
    }
}
