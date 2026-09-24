package com.k10.smsbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.k10.smsbridge.data.TransactionEntity
import com.k10.smsbridge.sms.SmsSearchFilters
import com.k10.smsbridge.sms.SmsSearchItem
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Screen { HOME, LOG, SEARCH, SETTINGS }

@Composable
fun BridgeApp(initialScreen: String = "settings", exit: () -> Unit = {}, vm: BridgeViewModel = viewModel()) {
    val start = if (initialScreen == "search") Screen.SEARCH else Screen.SETTINGS
    var screen by remember(initialScreen) { mutableStateOf(start) }
    val snackbarHostState = remember { SnackbarHostState() }
    BackHandler { if (screen == start) exit() else screen = start }
    LaunchedEffect(vm.messageVersion) {
        if (vm.messageVersion > 0 && vm.message.isNotBlank()) snackbarHostState.showSnackbar(vm.message)
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Crossfade(targetState = screen, label = "K10 page transition") { currentScreen ->
            val scrollState = rememberScrollState()
            val pageModifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .then(
                    if (currentScreen == Screen.HOME || currentScreen == Screen.SETTINGS) {
                        Modifier.verticalScroll(scrollState)
                    } else {
                        Modifier
                    }
                )
            Column(pageModifier.animateContentSize()) {
                when (currentScreen) {
                    Screen.HOME -> HomeScreen(vm, { screen = Screen.LOG }, { screen = Screen.SEARCH }, { screen = Screen.SETTINGS })
                    Screen.LOG -> TransactionLog(vm) { screen = start }
                    Screen.SEARCH -> SearchSmsScreen(vm) { if (start == Screen.SEARCH) exit() else screen = start }
                    Screen.SETTINGS -> SettingsScreen(vm) { if (start == Screen.SETTINGS) exit() else screen = start }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: BridgeViewModel, openLog: () -> Unit, openSearch: () -> Unit, openSettings: () -> Unit) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionGranted = it[Manifest.permission.RECEIVE_SMS] == true && it[Manifest.permission.READ_SMS] == true
        notificationGranted = Build.VERSION.SDK_INT < 33 || it[Manifest.permission.POST_NOTIFICATIONS] == true
        if (permissionGranted) vm.syncNow()
    }
    val pending by vm.pendingCount.collectAsState(0)
    val failed by vm.failedCount.collectAsState(0)
    val today by vm.todayCount.collectAsState(0)
    val transactions by vm.transactions.collectAsState(emptyList())
    val rules = vm.rules()
    val settings = vm.settings()
    val lastSuccess = transactions.filter { it.syncStatus == "SYNCED" }.maxOfOrNull { it.statusUpdatedAt }

    val startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val receivedToday = transactions.filter { it.smsReceivedTimestamp >= startOfToday }.sumOf { it.amountMinor }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text("K10 Pay", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Slice transaction assistant", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = openSettings) { Text("Settings") }
    }
    Spacer(Modifier.height(16.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("K10 SLICE ACCOUNT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(formatMoney(receivedToday), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Received today • $today transaction(s)")
            Spacer(Modifier.height(8.dp))
            Text("Account •••• ${rules.accountLast4}", style = MaterialTheme.typography.bodySmall)
        }
    }
    transactions.firstOrNull()?.let { latest ->
        Spacer(Modifier.height(12.dp))
        Text("Latest transaction", fontWeight = FontWeight.SemiBold)
        Card(onClick = openLog, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(latest.payerName, fontWeight = FontWeight.SemiBold)
                    Text(formatTimestamp(latest.smsReceivedTimestamp), style = MaterialTheme.typography.bodySmall)
                }
                Text(formatMoney(latest.amountMinor), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(if (settings.serviceEnabled && rules.enabled && permissionGranted) "🟢 Service Active" else "🔴 Service Not Running")
    InfoRow("SMS permission", if (permissionGranted) "Granted" else "Not Granted")
    InfoRow("Notification alerts", if (notificationGranted) "Granted" else "Not Granted")
    InfoRow("Current account filter", "xx${rules.accountLast4}")
    InfoRow("Backend connection", when (vm.connectionOk) { true -> "Connected"; false -> "Error"; null -> "Not tested" })
    InfoRow("Last successful sync", lastSuccess?.let(::formatTimestamp) ?: "Never")
    InfoRow("Eligible detected today", today.toString())
    InfoRow("Pending sync", pending.toString())
    InfoRow("Failed upload", failed.toString())
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    InfoRow("Matching Rules Version", rules.version)
    InfoRow("Last Rules Sync", vm.rulesLastSync().takeIf { it > 0 }?.let(::formatTimestamp) ?: "Using bundled rules")
    Spacer(Modifier.height(16.dp))
    if (!permissionGranted || !notificationGranted) Button(
        onClick = {
            permissionLauncher.launch(buildList {
                add(Manifest.permission.RECEIVE_SMS)
                add(Manifest.permission.READ_SMS)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray())
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Grant SMS & Notification Permissions") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.testConnection() }, enabled = !vm.isTestingConnection, modifier = Modifier.weight(1f)) {
            Text(if (vm.isTestingConnection) "Connecting…" else "Test Connection")
        }
        OutlinedButton(onClick = vm::syncNow, modifier = Modifier.weight(1f)) { Text("Retry / Sync") }
    }
    OutlinedButton(onClick = openSearch, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Scan existing SMS") }
    OutlinedButton(onClick = openLog, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("All Transactions") }
    OutlinedButton(onClick = openSettings, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Settings") }
}

@Composable
private fun TransactionLog(vm: BridgeViewModel, back: () -> Unit) {
    val transactions by vm.transactions.collectAsState(emptyList())
    var selected by remember { mutableStateOf<TransactionEntity?>(null) }
    var range by remember { mutableStateOf("Today") }
    val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val cutoff = when (range) {
        "15d" -> LocalDate.now().minusDays(14).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        "30d" -> LocalDate.now().minusDays(29).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        "All" -> Long.MIN_VALUE
        else -> todayStart
    }
    val visibleTransactions = transactions.filter { it.smsReceivedTimestamp >= cutoff }
    Header("Transaction Log", back)
    Text("Only eligible transactions detected or imported by K10 are shown.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Today", "15d", "30d", "All").forEach { option ->
            OutlinedButton(
                onClick = { range = option },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
            ) {
                Text(if (option == "All") "All" else option, maxLines = 1)
            }
        }
    }
    Text("${visibleTransactions.size} transaction(s)", style = MaterialTheme.typography.bodySmall)
    LazyColumn(Modifier.fillMaxSize()) {
        if (visibleTransactions.isEmpty()) {
            item { Text("No transactions in this period.", modifier = Modifier.padding(vertical = 20.dp)) }
        }
        items(visibleTransactions, key = { it.uniqueLocalId }) { item ->
            Card(onClick = { selected = item }, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(formatDate(item.smsReceivedTimestamp), fontWeight = FontWeight.SemiBold)
                    Text(item.payerName)
                    Text(formatMoney(item.amountMinor))
                    Text(statusText(item.syncStatus))
                }
            }
        }
    }
    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
            title = { Text(item.payerName) },
            text = {
                Column {
                    Text("Amount: ${formatMoney(item.amountMinor)}")
                    Text("Date: ${item.transactionDate}")
                    Text("Account: xx${item.accountLast4}")
                    Text("Method: ${item.paymentMethod}")
                    Text("Received: ${formatTimestamp(item.smsReceivedTimestamp)}")
                    Text("Status: ${statusText(item.syncStatus)}")
                    Spacer(Modifier.height(8.dp))
                    Text("Original eligible Slice SMS", fontWeight = FontWeight.Bold)
                    Text(item.rawEligibleSms)
                }
            }
        )
    }
}

@Composable
private fun SearchSmsScreen(vm: BridgeViewModel, back: () -> Unit) {
    val context = LocalContext.current
    var canRead by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canRead = it }
    var from by remember { mutableStateOf(LocalDate.now().minusDays(29).toString()) }
    var to by remember { mutableStateOf(LocalDate.now().toString()) }
    var sender by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(vm.rules().accountLast4) }
    var credit by remember { mutableStateOf("received, credited") }
    var rawText by remember { mutableStateOf("") }
    var eligibleOnly by remember { mutableStateOf(true) }
    val today = LocalDate.now()

    Header("Scan existing SMS", back)
    Text("New incoming transactions are captured and synced automatically. Use this screen to recover older messages.")
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("Quick range", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RangeButton("Today", Modifier.weight(1f)) { from = today.toString(); to = today.toString() }
                RangeButton("15d", Modifier.weight(1f)) { from = today.minusDays(14).toString(); to = today.toString() }
                RangeButton("30d", Modifier.weight(1f)) { from = today.minusDays(29).toString(); to = today.toString() }
                RangeButton("All", Modifier.weight(1f)) { from = "2000-01-01"; to = today.toString() }
            }
            Spacer(Modifier.height(12.dp))
            Text("Date range (YYYY-MM-DD)", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(from, { from = it }, "From", Modifier.weight(1f))
                Field(to, { to = it }, "To", Modifier.weight(1f))
            }
            Field(sender, { sender = it }, "Sender (optional)")
            Field(account, { account = it.filter(Char::isDigit).take(4) }, "Account last4")
            Field(credit, { credit = it }, "Credit keywords (comma-separated, any match)")
            Field(rawText, { rawText = it }, "Advanced local search text")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(eligibleOnly, { eligibleOnly = it })
                Text("Only eligible Slice transactions")
            }
            Button(onClick = {
                if (!canRead) launcher.launch(Manifest.permission.READ_SMS) else {
                    val start = runCatching { LocalDate.parse(from).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(0)
                    val end = runCatching { LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1 }.getOrDefault(System.currentTimeMillis())
                    vm.search(SmsSearchFilters(start, end, sender, account, credit, rawText, eligibleOnly))
                }
            }, enabled = !vm.isSearching, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(when {
                    !canRead -> "Grant permission"
                    vm.isSearching -> "Searching…"
                    else -> "Find transactions"
                })
            }
            Text("Sender is optional. Account digits and at least one credit keyword must match. OTP, debit and promotional messages remain blocked.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (vm.hasSearched) {
                Text(
                    if (vm.searchResults.isEmpty()) "No matching transactions found in this date range."
                    else "${vm.searchResults.size} matching transaction(s)",
                    fontWeight = FontWeight.SemiBold,
                    color = if (vm.searchResults.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
            }
            if (vm.searchResults.any { it.parseResult.eligible && !it.alreadyAdded }) {
                Button(onClick = vm::importAll, modifier = Modifier.fillMaxWidth()) {
                    Text("Save & Sync all eligible")
                }
            }
        }
        items(vm.searchResults) { item -> SearchResultCard(item, { vm.import(item) }, { vm.resync(item) }) }
    }
}

@Composable
private fun SearchResultCard(item: SmsSearchItem, import: () -> Unit, resync: () -> Unit) {
    val tx = item.parseResult.transaction
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(formatDate(item.receivedAt), fontWeight = FontWeight.SemiBold)
            Text(tx?.payerName ?: "Slice candidate")
            tx?.let {
                Text(formatMoney(it.amountMinor))
                Text("A/c xx${it.accountLast4}")
            }
            when {
                item.alreadyAdded -> {
                    Text(when(item.localStatus){
                        "PENDING" -> "Saved locally — waiting to sync"
                        "SYNCED" -> "Synced to server ✓"
                        "DUPLICATE" -> "Already present on server ✓"
                        "EXCLUDED" -> "Excluded from normal history"
                        "FAILED" -> "Sync failed"
                        "REJECTED" -> "Server rejected this transaction"
                        else -> "Saved locally — server status unknown"
                    })
                    if(item.localStatus != "PENDING" && item.localStatus != "EXCLUDED") {
                        OutlinedButton(onClick = resync) { Text("Resync") }
                    }
                }
                tx != null -> {
                    Text("Eligible — not yet saved")
                    Button(onClick = import) { Text("Save & Sync") }
                }
                else -> Text("Not eligible: ${item.parseResult.reason}")
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: BridgeViewModel, back: () -> Unit) {
    val saved = remember { vm.settings() }
    var url by remember { mutableStateOf(saved.backendUrl) }
    var token by remember { mutableStateOf(vm.token()) }
    var enabled by remember { mutableStateOf(saved.serviceEnabled) }
    var account by remember { mutableStateOf(vm.rules().accountLast4) }
    var senders by remember { mutableStateOf(vm.rules().allowedSenderIds.joinToString(", ")) }
    Header("Settings", back)
    Text("Account last4, received-credit wording and the banking format are compulsory. Known Slice sender IDs are retained as references, while new official Slice prefixes are accepted automatically.")
    Field(url, { url = it }, "Backend HTTPS URL")
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("Device / API token") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Field(account, { account = it.filter(Char::isDigit).take(4) }, "Expected account last4")
    Field(senders, { senders = it }, "Known Slice sender IDs (new prefixes are accepted)")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Service enabled", Modifier.weight(1f))
        Switch(enabled, { enabled = it })
    }
    Button(
        onClick = { vm.saveSettings(url, token, enabled, account, senders) },
        enabled = !vm.isSaving,
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (vm.isSaving) "Saving…" else "Save settings") }
    OutlinedButton(
        onClick = { vm.testConnection(url, token) },
        enabled = !vm.isTestingConnection,
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (vm.isTestingConnection) "Connecting…" else "Test Connection") }
    OutlinedButton(
        onClick = vm::syncRulesNow,
        enabled = !vm.isSyncing,
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (vm.isSyncing) "Syncing rules…" else "Sync Matching Rules Now") }
    Spacer(Modifier.height(12.dp))
    val rules = vm.rules()
    Text("Active rules", fontWeight = FontWeight.Bold)
    Text("Version ${rules.version} • ${rules.bankName} • xx${rules.accountLast4}")
    Text("Senders: ${rules.allowedSenderIds.joinToString()}")
}

@Composable
private fun Header(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = back, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text("‹ Back") }
        Spacer(Modifier.height(0.dp).weight(0.04f))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Field(value: String, change: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(value, change, label = { Text(label, maxLines = 1) }, singleLine = true, modifier = modifier.padding(vertical = 4.dp))
}

@Composable
private fun RangeButton(label: String, modifier: Modifier, action: () -> Unit) {
    OutlinedButton(
        onClick = action,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
    ) { Text(label, maxLines = 1) }
}

private fun formatMoney(minor: Long): String = NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(minor / 100.0)
private fun formatDate(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("dd MMM uuuu"))
private fun formatTimestamp(epoch: Long): String = DateTimeFormatter.ofPattern("dd MMM uuuu, h:mm a").format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))
private fun statusText(status: String): String = when (status) {
    "SYNCED" -> "Synced ✓"
    "DUPLICATE" -> "Already Synced ✓"
    "PENDING" -> "Pending Sync"
    "REJECTED" -> "Rejected"
    else -> "Failed upload"
}
