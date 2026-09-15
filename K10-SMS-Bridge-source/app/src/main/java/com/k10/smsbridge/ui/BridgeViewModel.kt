package com.k10.smsbridge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.k10.smsbridge.Graph
import com.k10.smsbridge.data.TransactionEntity
import com.k10.smsbridge.rules.BridgeSettings
import com.k10.smsbridge.sms.SmsSearchFilters
import com.k10.smsbridge.sms.SmsSearchItem
import com.k10.smsbridge.sms.SmsSearchRepository
import com.k10.smsbridge.sync.BackendClient
import com.k10.smsbridge.sync.SyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class BridgeViewModel(app: Application) : AndroidViewModel(app) {
    val transactions: Flow<List<TransactionEntity>> = Graph.database.transactions().observeAll()
    val pendingCount: Flow<Int> = Graph.database.transactions().observeCount("PENDING")
    val failedCount: Flow<Int> = Graph.database.transactions().observeCount("FAILED")
    val todayCount: Flow<Int> = Graph.database.transactions().observeDetectedSince(
        java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    )

    var searchResults: List<SmsSearchItem> by androidx.compose.runtime.mutableStateOf(emptyList<SmsSearchItem>())
        private set
    var message: String by androidx.compose.runtime.mutableStateOf("")
        private set
    var messageVersion: Int by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var connectionOk: Boolean? by androidx.compose.runtime.mutableStateOf<Boolean?>(null)
        private set
    var isSaving: Boolean by androidx.compose.runtime.mutableStateOf(false)
        private set
    var isTestingConnection: Boolean by androidx.compose.runtime.mutableStateOf(false)
        private set
    var isSearching: Boolean by androidx.compose.runtime.mutableStateOf(false)
        private set
    var isSyncing: Boolean by androidx.compose.runtime.mutableStateOf(false)
        private set
    var hasSearched: Boolean by androidx.compose.runtime.mutableStateOf(false)
        private set

    private val searchRepository = SmsSearchRepository(app, Graph.database.transactions())

    fun rules() = Graph.rules.current()
    fun rulesLastSync() = Graph.rules.lastSyncEpochMillis()
    fun settings() = Graph.rules.settings()
    fun token() = Graph.tokenStore.load()

    fun saveSettings(url: String, token: String, enabled: Boolean, accountLast4: String, senderIds: String) {
        if (isSaving) return
        isSaving = true
        runCatching {
            Graph.rules.saveSettings(BridgeSettings(url, enabled), accountLast4, senderIds.split(','))
            Graph.tokenStore.save(token)
        }.onSuccess { notifyUser("Settings saved securely") }
            .onFailure { notifyUser(it.message ?: "Could not save settings") }
        isSaving = false
    }

    fun testConnection(url: String = settings().backendUrl, token: String = token()) {
        if (isTestingConnection) return
        viewModelScope.launch {
            if (url.isBlank()) {
                notifyUser("Set the HTTPS backend URL first")
                connectionOk = false
                return@launch
            }
            isTestingConnection = true
            try {
                connectionOk = BackendClient.test(url.trimEnd('/'), token)
                notifyUser(if (connectionOk == true) "Backend connected" else "Backend connection failed")
            } finally {
                isTestingConnection = false
            }
        }
    }

    fun syncNow() {
        enqueueSyncWork()
        notifyUser("Pending transactions queued for sync")
    }

    fun syncRulesNow() {
        if (isSyncing) return
        viewModelScope.launch {
            val settings = settings()
            val token = token()
            if (settings.backendUrl.isBlank() || token.isBlank()) {
                notifyUser("Save the backend URL and API token first")
                return@launch
            }
            isSyncing = true
            runCatching {
                val json = BackendClient.fetchRules(settings.backendUrl.trimEnd('/'), token)
                Graph.rules.applyRemote(json).getOrThrow()
            }.onSuccess {
                enqueueSyncWork()
                notifyUser("Matching rules updated to ${it.version}")
            }.onFailure {
                notifyUser(it.message ?: "Matching rules sync failed")
            }
            isSyncing = false
        }
    }

    private fun enqueueSyncWork() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            SyncWorker.IMMEDIATE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    fun search(filters: SmsSearchFilters) {
        if (isSearching) return
        viewModelScope.launch {
            isSearching = true
            runCatching { searchRepository.search(filters, rules()) }
                .onSuccess {
                    searchResults = it
                    hasSearched = true
                    notifyUser(if (it.isEmpty()) "No matching transactions found" else "${it.size} matching transaction(s) found")
                }
                .onFailure {
                    hasSearched = true
                    notifyUser(it.message ?: "Search failed")
                }
            isSearching = false
        }
    }

    fun import(item: SmsSearchItem) {
        viewModelScope.launch {
            val added = searchRepository.import(item, rules())
            notifyUser(if (added) "Eligible transaction imported" else "Already added")
            if (added) {
                searchResults = searchResults.map { if (it === item) it.copy(alreadyAdded = true) else it }
                syncNow()
            }
        }
    }

    fun importAll() {
        viewModelScope.launch {
            var added = 0
            searchResults.filter { it.parseResult.eligible && !it.alreadyAdded }.forEach {
                if (searchRepository.import(it, rules())) added++
            }
            searchResults = searchResults.map { item ->
                if (item.parseResult.eligible) item.copy(alreadyAdded = true) else item
            }
            notifyUser(if (added == 0) "No new eligible transactions" else "$added transaction(s) imported")
            if (added > 0) syncNow()
        }
    }

    private fun notifyUser(value: String) {
        message = value
        messageVersion++
    }
}
