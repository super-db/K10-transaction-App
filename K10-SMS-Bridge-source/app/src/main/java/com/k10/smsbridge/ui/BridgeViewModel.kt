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
    var connectionOk: Boolean? by androidx.compose.runtime.mutableStateOf<Boolean?>(null)
        private set

    private val searchRepository = SmsSearchRepository(app, Graph.database.transactions())

    fun rules() = Graph.rules.current()
    fun rulesLastSync() = Graph.rules.lastSyncEpochMillis()
    fun settings() = Graph.rules.settings()
    fun token() = Graph.tokenStore.load()

    fun saveSettings(url: String, token: String, enabled: Boolean, accountLast4: String, senderIds: String) {
        runCatching {
            Graph.rules.saveSettings(BridgeSettings(url, enabled), accountLast4, senderIds.split(','))
            Graph.tokenStore.save(token)
        }.onSuccess { message = "Settings saved" }
            .onFailure { message = it.message ?: "Could not save settings" }
    }

    fun testConnection(url: String = settings().backendUrl, token: String = token()) {
        viewModelScope.launch {
            if (url.isBlank()) {
                message = "Set the HTTPS backend URL first"
                connectionOk = false
                return@launch
            }
            connectionOk = BackendClient.test(url.trimEnd('/'), token)
            message = if (connectionOk == true) "Backend connected" else "Backend connection failed"
        }
    }

    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            SyncWorker.IMMEDIATE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
        message = "Rules and pending transactions queued for sync"
    }

    fun search(filters: SmsSearchFilters) {
        viewModelScope.launch {
            runCatching { searchRepository.search(filters, rules()) }
                .onSuccess {
                    searchResults = it
                    message = "${it.size} matching candidate(s) found"
                }
                .onFailure { message = it.message ?: "Search failed" }
        }
    }

    fun import(item: SmsSearchItem) {
        viewModelScope.launch {
            val added = searchRepository.import(item, rules())
            message = if (added) "Eligible transaction imported" else "Already added"
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
            message = if (added == 0) "No new eligible transactions" else "$added transaction(s) imported"
            if (added > 0) syncNow()
        }
    }
}
