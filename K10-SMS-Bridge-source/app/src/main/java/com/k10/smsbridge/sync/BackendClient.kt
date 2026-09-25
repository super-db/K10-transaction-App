package com.k10.smsbridge.sync

import com.k10.smsbridge.data.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UploadResult(
    val status: String,
    val message: String?,
    val serverTransactionId: String? = null,
    val httpCode: Int? = null,
    val errorCode: String? = null,
    val traceId: String? = null,
    val serverConfirmedAt: String? = null
)

data class HealthResult(
    val healthy: Boolean,
    val httpCode: Int?,
    val message: String,
    val errorCode: String? = null
)

data class BackendDiagnosticComponent(
    val key: String,
    val status: String,
    val code: String,
    val detail: String,
    val suggestion: String? = null,
    val diagnosticId: String? = null
)

data class BackendDiagnosticLog(
    val timestamp: String,
    val level: String,
    val code: String,
    val stage: String,
    val message: String,
    val transactionId: String? = null,
    val referenceId: String? = null
)

data class BackendDiagnosticsReport(
    val components: List<BackendDiagnosticComponent>,
    val logs: List<BackendDiagnosticLog> = emptyList()
)

object BackendClient {
    suspend fun fetchRules(baseUrl: String, token: String): String = withContext(Dispatchers.IO) {
        request("$baseUrl/api/sms-matching-rules", "GET", token, null).let { (code, body) ->
            require(code in 200..299) { "Rules request failed with HTTP $code" }
            body
        }
    }

    suspend fun upload(baseUrl: String, token: String, item: TransactionEntity, fastAttempt: Boolean = false): UploadResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("unique_local_id", item.uniqueLocalId)
            put("duplicate_key", item.duplicateKey)
            put("payer_name", item.payerName)
            put("amount", item.amountMinor / 100.0)
            put("currency", item.currency)
            put("transaction_date", item.transactionDate)
            put("sms_received_timestamp", item.smsReceivedTimestamp)
            put("account_last4", item.accountLast4)
            put("payment_method", item.paymentMethod)
            put("sender_id", item.senderId)
            put("raw_eligible_sms", item.rawEligibleSms)
            put("source", item.source)
            put("created_at", item.createdAt)
        }
        val (code, body) = request(
            "$baseUrl/api/slice-transactions",
            "POST",
            token,
            payload.toString(),
            connectTimeout = if (fastAttempt) 5_000 else 15_000,
            readTimeout = if (fastAttempt) 7_000 else 20_000
        )
        val response = runCatching { JSONObject(body) }.getOrNull()
        val message = response?.optString("message")?.takeIf { it.isNotBlank() }
            ?: response?.optString("error")?.takeIf { it.isNotBlank() }
        val serverId = listOf("transaction_id", "transactionId", "id")
            .firstNotNullOfOrNull { key -> response?.optString(key)?.takeIf { it.isNotBlank() } }
        val errorCode = response?.optString("code")?.takeIf { it.isNotBlank() }
        val traceId = response?.optString("trace_id")?.takeIf { it.isNotBlank() }
        val serverConfirmedAt = response?.optString("server_confirmed_at")?.takeIf { it.isNotBlank() }
        when {
            code in 200..299 -> UploadResult(response?.optString("status", "success") ?: "success", message, serverId, code, errorCode, traceId, serverConfirmedAt)
            code == 409 -> UploadResult("duplicate", message ?: "Already stored on server", serverId, code, errorCode, traceId, serverConfirmedAt)
            code == 401 || code == 403 -> UploadResult("authentication_error", message ?: "Authentication failed", serverId, code, errorCode, traceId, serverConfirmedAt)
            code in 400..499 -> UploadResult("rejected", message ?: "Validation rejected", serverId, code, errorCode, traceId, serverConfirmedAt)
            else -> UploadResult("temporary_failure", message ?: "Server returned HTTP $code", serverId, code, errorCode, traceId, serverConfirmedAt)
        }
    }

    suspend fun test(baseUrl: String, token: String): Boolean = withContext(Dispatchers.IO) {
        health(baseUrl, token).healthy
    }

    suspend fun health(baseUrl: String, token: String): HealthResult = withContext(Dispatchers.IO) {
        runCatching {
            val (code, body) = request("$baseUrl/api/health", "GET", token, null)
            val json = runCatching { JSONObject(body) }.getOrNull()
            val message = json?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json?.optString("error")?.takeIf { it.isNotBlank() }
                ?: if (code in 200..299) "Backend authenticated" else "Backend returned HTTP $code"
            HealthResult(code in 200..299, code, message, json?.optString("code")?.takeIf { it.isNotBlank() })
        }.getOrElse { HealthResult(false, null, safeFailure(it)) }
    }

    /**
     * Requires the backend diagnostics endpoint introduced with K10 Pay 4.4.
     * Older servers return an explicit unsupported component instead of a false green result.
     */
    suspend fun diagnostics(baseUrl: String, token: String): BackendDiagnosticsReport = withContext(Dispatchers.IO) {
        runCatching {
            val (code, body) = request("$baseUrl/api/k10-pay/diagnostics", "POST", token, "{}")
            if (code == 404) return@runCatching BackendDiagnosticsReport(listOf(
                BackendDiagnosticComponent("backend_diagnostics", "unavailable", "DIAGNOSTICS_NOT_DEPLOYED", "Backend diagnostic endpoint is not deployed", "Deploy the K10 Pay backend diagnostics route")
            ))
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (code !in 200..299 || json == null) return@runCatching BackendDiagnosticsReport(listOf(
                BackendDiagnosticComponent("backend_diagnostics", "failed", json?.optString("code", "DIAGNOSTICS_HTTP_$code") ?: "DIAGNOSTICS_HTTP_$code", json?.optString("error") ?: "Diagnostic request failed with HTTP $code", "Check the backend deployment and SMS Bridge token", json?.optString("diagnosticId")?.takeIf { it.isNotBlank() })
            ))
            val diagnosticId = json.optString("diagnosticId").takeIf { it.isNotBlank() }
            val array = json.optJSONArray("components")
            val components = if (array == null) emptyList() else (0 until array.length()).map { index ->
                val value = array.getJSONObject(index)
                BackendDiagnosticComponent(
                    key = value.optString("key", "component_$index"),
                    status = value.optString("status", "unknown"),
                    code = value.optString("code", "DIAGNOSTIC_CODE_MISSING"),
                    detail = value.optString("detail", "No detail supplied"),
                    suggestion = value.optString("suggestion").takeIf { it.isNotBlank() },
                    diagnosticId = diagnosticId
                )
            }
            val logArray = json.optJSONArray("logs")
            val logs = if (logArray == null) emptyList() else (0 until logArray.length()).map { index ->
                val value = logArray.getJSONObject(index)
                BackendDiagnosticLog(
                    timestamp = value.optString("timestamp"),
                    level = value.optString("level", "INFO"),
                    code = value.optString("code", "SERVER_EVENT"),
                    stage = value.optString("stage", "server"),
                    message = value.optString("message", "Server event"),
                    transactionId = value.optString("transactionId").takeIf { it.isNotBlank() },
                    referenceId = value.optString("referenceId").takeIf { it.isNotBlank() }
                )
            }
            BackendDiagnosticsReport(components, logs)
        }.getOrElse {
            BackendDiagnosticsReport(listOf(BackendDiagnosticComponent("backend_diagnostics", "failed", "DIAGNOSTICS_CONNECTION_FAILED", safeFailure(it), "Check internet access and backend availability")))
        }
    }

    private fun request(
        url: String,
        method: String,
        token: String,
        body: String?,
        connectTimeout: Int = 15_000,
        readTimeout: Int = 20_000
    ): Pair<Int, String> {
        require(url.startsWith("https://")) { "Only HTTPS endpoints are allowed" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    fun safeFailure(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException -> "Connection timed out"
        is java.net.UnknownHostException -> "Backend address could not be reached"
        is java.net.ConnectException -> "Backend refused the connection"
        else -> error.message?.take(180)?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
    }
}
