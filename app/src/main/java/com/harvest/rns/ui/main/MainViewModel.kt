package com.harvest.rns.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.harvest.rns.data.db.HarvestDatabase
import com.harvest.rns.data.model.ConnectionStatus
import com.harvest.rns.data.model.DiscoveredNode
import com.harvest.rns.data.model.HarvestRecord
import com.harvest.rns.data.model.HarvesterSummary
import com.harvest.rns.data.model.RadioConfig
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
    fun setSelectedDate(date: String) { _selectedDate.value = date }

    // ─── Records ──────────────────────────────────────────────────────────────
    val allRecords:     LiveData<List<HarvestRecord>>     = repository.allRecords
    val recordCount:    LiveData<Int>                     = repository.recordCount
    val availableDates: LiveData<List<String>>            = repository.availableDates
    val allSummaries:   LiveData<List<HarvesterSummary>>  = repository.allSummaries

    fun getSummaryForDate(date: String) = dao.getSummaryByDate(date)

    // ─── Service Stats ────────────────────────────────────────────────────────
    val messageCount:   LiveData<Int>    = RNSReceiverService.messageCount.asLiveData()
    val duplicateCount: LiveData<Int>    = RNSReceiverService.duplicateCount.asLiveData()
    val serviceStatus:  LiveData<String> = RNSReceiverService.serviceStatus.asLiveData()
    val lastMessageTime:LiveData<Long>   = RNSReceiverService.lastMessageTime.asLiveData()

    // ─── Discovered Nodes ─────────────────────────────────────────────────────
    val discoveredNodes: LiveData<Map<String, DiscoveredNode>> =
        RNSReceiverService.discoveredNodes.asLiveData()

    val discoveredNodeList: LiveData<List<DiscoveredNode>> =
        RNSReceiverService.discoveredNodes
            .map { it.values.sortedByDescending { n -> n.lastSeen } }
            .asLiveData()

    fun clearDiscoveredNodes() {
        // Delegate to the service singleton
        RNSReceiverService.discoveredNodes  // trigger via binder if needed
        _boundService?.clearDiscoveredNodes()
    }

    // ─── Radio Config ─────────────────────────────────────────────────────────
    val radioConfig: LiveData<RadioConfig> = RNSReceiverService.radioConfig.asLiveData()
    val ownAddress:  LiveData<String>      = RNSReceiverService.ownAddress.asLiveData()

    fun applyRadioConfig(config: RadioConfig) {
        _boundService?.applyRadioConfig(config)
    }

    // ─── Connection Status ────────────────────────────────────────────────────
    private val _connectionStatus = MutableLiveData<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: LiveData<ConnectionStatus> = _connectionStatus
    fun updateConnectionStatus(status: ConnectionStatus) { _connectionStatus.postValue(status) }

    // ─── Service reference (set by MainActivity after binding) ────────────────
    private var _boundService: RNSReceiverService? = null
    fun bindService(service: RNSReceiverService) { _boundService = service }
    fun unbindService() { _boundService = null }

    // ─── Data Management ──────────────────────────────────────────────────────
    fun clearAllData() { viewModelScope.launch { repository.deleteAll() } }

    private fun todayDate() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
