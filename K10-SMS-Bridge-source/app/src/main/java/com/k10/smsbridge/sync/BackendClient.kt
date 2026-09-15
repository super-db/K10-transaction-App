package com.k10.smsbridge.sync

import com.k10.smsbridge.data.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UploadResult(val status: String, val message: String?)

object BackendClient {
    suspend fun fetchRules(baseUrl: String, token: String): String = withContext(Dispatchers.IO) {
        request("$baseUrl/api/sms-matching-rules", "GET", token, null).let { (code, body) ->
            require(code in 200..299) { "Rules request failed with HTTP $code" }
            body
        }
    }

    suspend fun upload(baseUrl: String, token: String, item: TransactionEntity): UploadResult = withContext(Dispatchers.IO) {
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
        val (code, body) = request("$baseUrl/api/slice-transactions", "POST", token, payload.toString())
        val response = runCatching { JSONObject(body) }.getOrNull()
        when {
            code in 200..299 -> UploadResult(response?.optString("status", "success") ?: "success", response?.optString("message"))
            code == 409 -> UploadResult("duplicate", response?.optString("message"))
            code == 401 || code == 403 -> UploadResult("authentication_error", "Authentication failed")
            code in 400..499 -> UploadResult("rejected", response?.optString("message", "Validation rejected") ?: "Validation rejected")
            else -> throw IllegalStateException("Upload failed with HTTP $code")
        }
    }

    suspend fun test(baseUrl: String, token: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { request("$baseUrl/api/health", "GET", token, null).first in 200..299 }.getOrDefault(false)
    }

    private fun request(url: String, method: String, token: String, body: String?): Pair<Int, String> {
        require(url.startsWith("https://")) { "Only HTTPS endpoints are allowed" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
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
}
