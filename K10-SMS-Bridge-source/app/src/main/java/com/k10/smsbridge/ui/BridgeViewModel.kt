package com.k10.smsbridge.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.k10.smsbridge.Graph
import com.k10.smsbridge.data.DiagnosticEventLogEntity
import com.k10.smsbridge.data.TransactionEntity
import com.k10.smsbridge.diagnostics.DiagnosticEventLog
import com.k10.smsbridge.rules.BridgeSettings
import com.k10.smsbridge.mobile.DeviceRegistrationStatus
import com.k10.smsbridge.sms.SmsSearchFilters
import com.k10.smsbridge.sms.SmsSearchItem
import com.k10.smsbridge.sms.SmsSearchRepository
import com.k10.smsbridge.sync.BackendClient
import com.k10.smsbridge.sync.ConfirmedSync
import com.k10.smsbridge.sync.TransactionSyncCoordinator
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class SystemDiagnostic(
    val key: String,
    val title: String,
    val status: String,
    val detail: String,
    val suggestion: String? = null,
    val code: String = "${key.uppercase()}_${status.uppercase()}",
    val diagnosticId: String? = null
)

data class ServerDiagnosticLogLine(
    val timestamp: String,
    val level: String,
    val code: String,
    val stage: String,
    val message: String,
    val transactionId: String? = null,
    val referenceId: String? = null
)

class BridgeViewModel(app: Application) : AndroidViewModel(app) {
    val localDiagnosticLogs: Flow<List<DiagnosticEventLogEntity>> = Graph.database.diagnosticLogs().observeRecent(100)
    val transactions: Flow<List<TransactionEntity>> = Graph.database.transactions().observeAll().map { rows ->
        val excluded = Graph.rules.current().excludedPayerNames.map { it.trim().lowercase() }.toSet()
        rows.filter { it.syncStatus != "EXCLUDED" && it.payerName.trim().lowercase() !in excluded }
    }
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
    var isSyncingAll: Boolean by androidx.compose.runtime.mutableStateOf(false)
        private set
    var hasSearched: Boolean by androidx.compose.runtime.mutableStateOf(false)
        private set
    var isRunningDiagnostics: Boolean by androidx.compose.runtime.mutableStateOf(false)
        private set
    var diagnosticChecks: List<SystemDiagnostic> by androidx.compose.runtime.mutableStateOf(emptyList())
        private set
    var diagnosticsRunAt: Long by androidx.compose.runtime.mutableLongStateOf(0L)
        private set
    var serverDiagnosticLogs: List<ServerDiagnosticLogLine> by androidx.compose.runtime.mutableStateOf(emptyList())
        private set

    private val searchRepository = SmsSearchRepository(app, Graph.database.transactions())
    private var lastSearchFilters: SmsSearchFilters? = null

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
        TransactionSyncCoordinator.enqueueRecovery(getApplication(), expedited = true)
    }

    fun search(filters: SmsSearchFilters) {
        if (isSearching) return
        viewModelScope.launch {
            isSearching = true
            lastSearchFilters = filters
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
            notifyUser(if (added) "Transaction saved and queued for sync" else "Already saved")
            if (added) {
                searchResults = searchResults.map { if (it === item) it.copy(alreadyAdded = true, localStatus = "PENDING") else it }
                syncNow()
            }
        }
    }

    fun resync(item: SmsSearchItem) {
        if (isSyncingAll) return
        viewModelScope.launch {
            val queued = searchRepository.retry(item, rules())
            if (queued) {
                searchResults = searchResults.map { if (it === item) it.copy(localStatus = "PENDING") else it }
                isSyncingAll = true
                runCatching { ConfirmedSync.run(getApplication(), scanFullHistory = false, recheckAll = false) }
                    .onSuccess {
                        refreshSearchResults()
                        notifyUser(it.userMessage())
                    }
                    .onFailure { notifyUser(it.message ?: "Transaction sync failed") }
                isSyncingAll = false
            } else {
                notifyUser("Could not find the saved transaction")
            }
        }
    }

    fun syncAll() {
        if (isSyncingAll) return
        viewModelScope.launch {
            isSyncingAll = true
            notifyUser("Scanning SMS and checking server…")
            runCatching { ConfirmedSync.run(getApplication(), scanFullHistory = true, recheckAll = true) }
                .onSuccess {
                    refreshSearchResults()
                    notifyUser(it.userMessage())
                }
                .onFailure { notifyUser(it.message ?: "Sync all failed") }
            isSyncingAll = false
        }
    }

    fun runFullDiagnostics() {
        if (isRunningDiagnostics) return
        viewModelScope.launch {
            isRunningDiagnostics = true
            diagnosticChecks = emptyList()
            serverDiagnosticLogs = emptyList()
            DiagnosticEventLog.info("DIAGNOSTIC_RUN_STARTED", "diagnostics", "Full system diagnostic started")
            val app = getApplication<Application>()
            val checks = mutableListOf<SystemDiagnostic>()
            val smsGranted = androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
            checks += SystemDiagnostic("sms_permissions", "SMS access", if (smsGranted) "working" else "failed", if (smsGranted) "Receive and inbox recovery permissions are granted" else "SMS permission is missing", if (smsGranted) null else "Grant SMS permissions from Android settings")

            val notificationGranted = (Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled()
            checks += SystemDiagnostic("notification_permission", "Android notifications", if (notificationGranted) "working" else "failed", if (notificationGranted) "Notification permission is granted" else "Notification permission is blocked", if (notificationGranted) null else "Enable K10 Pay notifications in Android settings")
            checks += SystemDiagnostic("voice_engine", "Voice announcements", if (Graph.announcer.isReady()) "working" else "pending", if (Graph.announcer.isReady()) "Android text-to-speech is ready" else "Text-to-speech is still unavailable", if (Graph.announcer.isReady()) null else "Check the phone's text-to-speech engine and language data")

            val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val unrestricted = power.isIgnoringBatteryOptimizations(app.packageName)
            checks += SystemDiagnostic("battery", "Background reliability", if (unrestricted) "working" else "warning", if (unrestricted) "K10 Pay is not battery restricted" else "Android may delay retry work while the app is idle", if (unrestricted) null else "Set K10 Pay battery usage to Unrestricted on the Developer phone")

            val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivity.activeNetwork
            val capabilities = network?.let(connectivity::getNetworkCapabilities)
            val online = capabilities?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
            checks += SystemDiagnostic("internet", "Internet", if (online) "working" else "failed", if (online) "Validated network is available" else "No validated internet connection", if (online) null else "Connect to mobile data or Wi-Fi and run the diagnostic again")

            val rows = Graph.database.transactions().snapshot()
            val pending = rows.count { it.syncStatus in setOf("PENDING", "UPLOADING") }
            val failed = rows.count { it.syncStatus in setOf("FAILED", "REJECTED") }
            val latest = rows.maxByOrNull { it.smsReceivedTimestamp }
            checks += SystemDiagnostic("local_database", "Local transaction store", "working", "${rows.size} saved · latest ${latest?.payerName ?: "none"}")
            checks += SystemDiagnostic("upload_queue", "Server upload queue", when { failed > 0 -> "failed"; pending > 0 -> "pending"; else -> "working" }, "$pending pending · $failed failed", when { failed > 0 -> "Tap Sync pending, then inspect the failed transaction response"; pending > 0 -> "Keep internet enabled or tap Sync pending"; else -> null })

            val settings = settings()
            val token = token()
            if (settings.backendUrl.isBlank() || token.isBlank()) {
                checks += SystemDiagnostic("backend", "Backend API", "failed", "Backend URL or API token is missing", "Save the connection configuration below")
            } else {
                val health = com.k10.smsbridge.sync.BackendClient.health(settings.backendUrl.trimEnd('/'), token)
                checks += SystemDiagnostic("backend", "Backend API & authentication", if (health.healthy) "working" else "failed", "${health.message}${health.httpCode?.let { " · HTTP $it" }.orEmpty()}", if (health.healthy) null else "Verify the deployed API and SMS Bridge token")
                if (health.healthy) {
                    val report = com.k10.smsbridge.sync.BackendClient.diagnostics(settings.backendUrl.trimEnd('/'), token)
                    report.components.forEach { component ->
                        checks += SystemDiagnostic(component.key, diagnosticTitle(component.key), component.status, component.detail, component.suggestion, component.code, component.diagnosticId)
                    }
                    serverDiagnosticLogs = report.logs.map {
                        ServerDiagnosticLogLine(it.timestamp, it.level, it.code, it.stage, it.message, it.transactionId, it.referenceId)
                    }
                }
            }

            val fcmToken = firebaseToken()
            checks += SystemDiagnostic("firebase_device", "Firebase device token", if (fcmToken.isNullOrBlank()) "failed" else "working", if (fcmToken.isNullOrBlank()) "This phone could not obtain an FCM token" else "This phone has a current Firebase token", if (fcmToken.isNullOrBlank()) "Check Google services configuration and reconnect the phone" else null)
            val registration = DeviceRegistrationStatus.read(app)
            if (Graph.mobileSession.load() == null) {
                checks += SystemDiagnostic("local_device_registration", "This phone's push registration", "failed", "No signed-in K10 Pay session is available", "Sign in again and rerun diagnostics")
            } else {
                checks += SystemDiagnostic(
                    "local_device_registration",
                    "This phone's push registration",
                    if (registration.registered) "working" else "failed",
                    registration.message,
                    if (registration.registered) "Backend diagnostics will additionally verify every staff phone" else "Sign in again or check the mobile device-registration endpoint"
                )
            }

            diagnosticChecks = checks.distinctBy { it.key }
            diagnosticsRunAt = System.currentTimeMillis()
            DiagnosticEventLog.info("DIAGNOSTIC_RUN_COMPLETED", "diagnostics", "Full system diagnostic completed with ${diagnosticChecks.count { it.status == "failed" }} failed check(s)")
            isRunningDiagnostics = false
        }
    }

    fun retryPendingFromDiagnostics() {
        TransactionSyncCoordinator.enqueueRecovery(getApplication(), expedited = true)
        notifyUser("Pending transactions queued for immediate retry")
    }

    private suspend fun firebaseToken(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (continuation.isActive) continuation.resume(if (task.isSuccessful) task.result else null)
        }
    }

    private fun diagnosticTitle(key: String) = when (key) {
        "upload" -> "Transaction upload"
        "d1" -> "Cloudflare D1"
        "firebase" -> "Firebase delivery"
        "device_registration" -> "Registered staff devices"
        "notification_outbox" -> "Notification queue"
        else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private suspend fun refreshSearchResults() {
        val filters = lastSearchFilters ?: return
        searchResults = searchRepository.search(filters, rules())
    }

    fun importAll() {
        viewModelScope.launch {
            var added = 0
            searchResults.filter { it.parseResult.eligible && !it.alreadyAdded }.forEach {
                if (searchRepository.import(it, rules())) added++
            }
            searchResults = searchResults.map { item ->
                if (item.parseResult.eligible) item.copy(alreadyAdded = true, localStatus = item.localStatus ?: "PENDING") else item
            }
            notifyUser(if (added == 0) "No new eligible transactions" else "$added transaction(s) saved and queued for sync")
            if (added > 0) syncNow()
        }
    }

    private fun notifyUser(value: String) {
        message = value
        messageVersion++
    }
}
