package com.harvest.rns.ui.main

import android.app.Application
import android.content.Context
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

    // ─── Date navigation (shared between Incoming and Summary tabs) ───────────
    private val _selectedDate = MutableLiveData(todayDate())
    val selectedDate: LiveData<String> = _selectedDate

    fun goToPreviousDate() {
        _selectedDate.value = offsetDate(_selectedDate.value ?: todayDate(), -1)
    }
    fun goToNextDate() {
        val next = offsetDate(_selectedDate.value ?: todayDate(), +1)
        if (next <= todayDate()) _selectedDate.value = next
    }
    fun canGoForward(): Boolean = (_selectedDate.value ?: todayDate()) < todayDate()

    private fun offsetDate(date: String, days: Int): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance().also { it.time = sdf.parse(date) ?: Date() }
        cal.add(Calendar.DAY_OF_YEAR, days)
        return sdf.format(cal.time)
    }

    // ─── Records ──────────────────────────────────────────────────────────────
    val allRecords:     LiveData<List<HarvestRecord>>    = repository.allRecords
    val recordCount:    LiveData<Int>                    = repository.recordCount
    val availableDates: LiveData<List<String>>           = repository.availableDates
    val allSummaries:   LiveData<List<HarvesterSummary>> = repository.allSummaries

    fun getRecordsByDate(date: String) = dao.getRecordsByDate(date)
    fun getSummaryForDate(date: String) = dao.getSummaryByDate(date)

    // ─── Node nicknames (stored in SharedPreferences) ─────────────────────────
    private val nickPrefs by lazy {
        application.getSharedPreferences("node_nicknames", Context.MODE_PRIVATE)
    }

    fun getNickname(hash: String): String? = nickPrefs.getString(hash, null)

    fun setNickname(hash: String, nick: String) {
        if (nick.isBlank()) nickPrefs.edit().remove(hash).apply()
        else nickPrefs.edit().putString(hash, nick.trim()).apply()
        // Trigger refresh of nodes list
        val current = RNSReceiverService.discoveredNodes.value.toMutableMap()
        current[hash]?.let { node ->
            current[hash] = node.copy(displayName = nick.trim().ifBlank { null })
            // Note: this updates the in-memory map but won't persist across service restart
            // The nickPrefs is the persistent store
        }
        _nickUpdate.value = System.currentTimeMillis() // trigger UI refresh
    }

    private val _nickUpdate = MutableLiveData<Long>()
    val nickUpdate: LiveData<Long> = _nickUpdate

    // ─── Service stats ────────────────────────────────────────────────────────
    val messageCount:    LiveData<Int>    = RNSReceiverService.messageCount.asLiveData()
    val duplicateCount:  LiveData<Int>    = RNSReceiverService.duplicateCount.asLiveData()
    val serviceStatus:   LiveData<String> = RNSReceiverService.serviceStatus.asLiveData()
    val lastMessageTime: LiveData<Long>   = RNSReceiverService.lastMessageTime.asLiveData()

    // ─── Nodes ────────────────────────────────────────────────────────────────
    val discoveredNodeList: LiveData<List<DiscoveredNode>> =
        RNSReceiverService.discoveredNodes
            .map { map ->
                map.values
                    .sortedByDescending { it.lastSeen }
                    .map { node ->
                        // Overlay nickname from prefs
                        val nick = nickPrefs.getString(node.destinationHash, null)
                        if (nick != null) node.copy(displayName = nick) else node
                    }
            }
            .asLiveData()

    fun clearDiscoveredNodes() {
        // Nodes are observed from RNSReceiverService.discoveredNodes flow
        // No direct clear needed — flow updates automatically
    }

    // ─── Radio ────────────────────────────────────────────────────────────────
    val radioConfig: LiveData<RadioConfig> = RNSReceiverService.radioConfig.asLiveData()
    val ownAddress:  LiveData<String>      = RNSReceiverService.ownAddress.asLiveData()

    fun applyRadioConfig(config: RadioConfig) {
        RNSReceiverService.radioConfig.value  // read current
        val intent = android.content.Intent(getApplication(), com.harvest.rns.network.RNSReceiverService::class.java)
        intent.action = com.harvest.rns.network.RNSReceiverService.ACTION_APPLY_RADIO
        getApplication<android.app.Application>().startService(intent)
    }

    // ─── Connection ───────────────────────────────────────────────────────────
    private val _connectionStatus = MutableLiveData<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: LiveData<ConnectionStatus> = _connectionStatus
    fun updateConnectionStatus(s: ConnectionStatus) { _connectionStatus.postValue(s) }

    // ─── Service binding ──────────────────────────────────────────────────────
    private var _boundService: RNSReceiverService? = null
    fun bindService(s: RNSReceiverService) {
        _boundService = s
        // Apply saved radio config on connect
        viewModelScope.launch {
            val prefs = getApplication<android.app.Application>()
                .getSharedPreferences(RadioConfig.PREFS_NAME, Context.MODE_PRIVATE)
            val config = RadioConfig(
                433_025_000L,
                prefs.getInt(RadioConfig.PREF_BW, RadioConfig.DEFAULT.bandwidthHz),
                prefs.getInt(RadioConfig.PREF_SF, RadioConfig.DEFAULT.spreadingFactor),
                prefs.getInt(RadioConfig.PREF_CR, RadioConfig.DEFAULT.codingRate),
                prefs.getInt(RadioConfig.PREF_TXPOWER, RadioConfig.DEFAULT.txPower)
            )
            applyRadioConfig(config)
        }
    }
    fun unbindService() { _boundService = null }

    // ─── Data management ──────────────────────────────────────────────────────
    fun clearAllData() { viewModelScope.launch { repository.deleteAll() } }

    fun todayDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
