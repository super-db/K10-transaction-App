package com.k10.smsbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
fun BridgeApp(vm: BridgeViewModel = viewModel()) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            when (screen) {
                Screen.HOME -> HomeScreen(vm, { screen = Screen.LOG }, { screen = Screen.SEARCH }, { screen = Screen.SETTINGS })
                Screen.LOG -> TransactionLog(vm) { screen = Screen.HOME }
                Screen.SEARCH -> SearchSmsScreen(vm) { screen = Screen.HOME }
                Screen.SETTINGS -> SettingsScreen(vm) { screen = Screen.HOME }
            }
            if (vm.message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(vm.message, color = MaterialTheme.colorScheme.primary)
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
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionGranted = it[Manifest.permission.RECEIVE_SMS] == true && it[Manifest.permission.READ_SMS] == true
    }
    val pending by vm.pendingCount.collectAsState(0)
    val failed by vm.failedCount.collectAsState(0)
    val today by vm.todayCount.collectAsState(0)
    val transactions by vm.transactions.collectAsState(emptyList())
    val rules = vm.rules()
    val settings = vm.settings()
    val lastSuccess = transactions.filter { it.syncStatus == "SYNCED" }.maxOfOrNull { it.statusUpdatedAt }

    Text("K10 SMS Bridge", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    Text(if (settings.serviceEnabled && rules.enabled && permissionGranted) "🟢 Service Active" else "🔴 Service Not Running")
    InfoRow("SMS permission", if (permissionGranted) "Granted" else "Not Granted")
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
    if (!permissionGranted) Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)) }) { Text("Grant SMS Permission") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.testConnection() }) { Text("Test Connection") }
        OutlinedButton(onClick = vm::syncNow) { Text("Retry / Sync") }
    }
    OutlinedButton(onClick = openSearch, modifier = Modifier.fillMaxWidth()) { Text("Search SMS") }
    OutlinedButton(onClick = openLog, modifier = Modifier.fillMaxWidth()) { Text("View Transaction Log") }
    OutlinedButton(onClick = openSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
}

@Composable
private fun TransactionLog(vm: BridgeViewModel, back: () -> Unit) {
    val transactions by vm.transactions.collectAsState(emptyList())
    var selected by remember { mutableStateOf<TransactionEntity?>(null) }
    Header("Transaction Log", back)
    Text("Only eligible transactions detected or imported by K10 are shown.")
    LazyColumn(Modifier.fillMaxSize()) {
        items(transactions, key = { it.uniqueLocalId }) { item ->
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
    var from by remember { mutableStateOf(LocalDate.now().minusDays(7).toString()) }
    var to by remember { mutableStateOf(LocalDate.now().toString()) }
    var sender by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(vm.rules().accountLast4) }
    var credit by remember { mutableStateOf("received") }
    var rawText by remember { mutableStateOf("") }
    var eligibleOnly by remember { mutableStateOf(true) }

    Header("Search SMS", back)
    Text("Search runs only on this phone. Results are never uploaded until you choose Import.")
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(from, { from = it }, "Date From (YYYY-MM-DD)", Modifier.weight(1f))
                Field(to, { to = it }, "Date To (YYYY-MM-DD)", Modifier.weight(1f))
            }
            Field(sender, { sender = it }, "Sender")
            Field(account, { account = it.filter(Char::isDigit).take(4) }, "Account last4")
            Field(credit, { credit = it }, "Credit keyword")
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
            }) { Text(if (canRead) "Search on device" else "Grant permission") }
            Text("Turning the toggle off can show only Slice/account/credit candidates that fail a banking pattern; unrelated messages remain hidden.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (vm.searchResults.any { it.parseResult.eligible && !it.alreadyAdded }) {
                OutlinedButton(onClick = vm::importAll) { Text("Import All Eligible") }
            }
        }
        items(vm.searchResults) { item -> SearchResultCard(item) { vm.import(item) } }
    }
}

@Composable
private fun SearchResultCard(item: SmsSearchItem, import: () -> Unit) {
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
                item.alreadyAdded -> Text("Already added ✓")
                tx != null -> {
                    Text("Eligible — Ready to import")
                    Button(onClick = import) { Text("Import") }
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
    Text("Remote matching rules can change approved Slice senders, account last4, and parsing templates. Local privacy checks cannot be disabled.")
    Field(url, { url = it }, "Backend HTTPS URL")
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("Device / API token") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Field(account, { account = it.filter(Char::isDigit).take(4) }, "Expected account last4")
    Field(senders, { senders = it }, "Approved Slice sender IDs (comma-separated)")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Service enabled", Modifier.weight(1f))
        Switch(enabled, { enabled = it })
    }
    Button(onClick = { vm.saveSettings(url, token, enabled, account, senders) }) { Text("Save") }
    OutlinedButton(onClick = { vm.testConnection(url, token) }) { Text("Test Connection") }
    OutlinedButton(onClick = vm::syncNow) { Text("Sync Matching Rules Now") }
    Spacer(Modifier.height(12.dp))
    val rules = vm.rules()
    Text("Active rules", fontWeight = FontWeight.Bold)
    Text("Version ${rules.version} • ${rules.bankName} • xx${rules.accountLast4}")
    Text("Senders: ${rules.allowedSenderIds.joinToString()}")
}

@Composable
private fun Header(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = back) { Text("Back") }
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
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, modifier = modifier)
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
