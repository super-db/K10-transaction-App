package com.k10.smsbridge.sms

import android.content.Context
import android.provider.Telephony
import com.k10.smsbridge.data.TransactionDao
import com.k10.smsbridge.data.toEntity
import com.k10.smsbridge.rules.RuleConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SmsSearchFilters(
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val sender: String = "",
    val accountLast4: String = "",
    val creditKeyword: String = "",
    val searchText: String = "",
    val eligibleOnly: Boolean = true
)

data class SmsSearchItem(
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val parseResult: ParseResult,
    val alreadyAdded: Boolean,
    val localStatus: String? = null,
    val localId: String? = null,
    val serverMessage: String? = null
)

class SmsSearchRepository(private val context: Context, private val dao: TransactionDao) {
    suspend fun search(filters: SmsSearchFilters, rules: RuleConfig): List<SmsSearchItem> = withContext(Dispatchers.IO) {
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} <= ?"
        val args = arrayOf(filters.fromEpochMillis.toString(), filters.toEpochMillis.toString())
        val results = mutableListOf<SmsSearchItem>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            selection,
            args,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val senderIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext() && results.size < 250) {
                val sender = cursor.getString(senderIndex).orEmpty()
                val body = cursor.getString(bodyIndex).orEmpty()
                val receivedAt = cursor.getLong(dateIndex)
                if (!SmsParser.isFinanciallyScopedCandidate(sender, body, rules)) continue
                if (filters.sender.isNotBlank() && !sender.contains(filters.sender, true)) continue
                if (filters.accountLast4.isNotBlank() && !body.contains(filters.accountLast4)) continue
                val creditTerms = filters.creditKeyword.split(',').map { it.trim() }.filter { it.isNotBlank() }
                if (creditTerms.isNotEmpty() && creditTerms.none { body.contains(it, true) }) continue
                val rawTerms = filters.searchText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                if (rawTerms.any { !body.contains(it, true) }) continue
                val parsed = SmsParser.parse(sender, body, receivedAt, rules)
                if (filters.eligibleOnly && !parsed.eligible) continue
                val duplicate = parsed.transaction?.let { dao.findDuplicate(it.duplicateKey) }
                results += SmsSearchItem(sender, body, receivedAt, parsed, duplicate != null, duplicate?.syncStatus, duplicate?.uniqueLocalId, duplicate?.serverMessage)
            }
        }
        results
    }

    suspend fun import(item: SmsSearchItem, currentRules: RuleConfig): Boolean {
        val transaction = SmsParser.parse(item.sender, item.body, item.receivedAt, currentRules).transaction ?: return false
        if (dao.findDuplicate(transaction.duplicateKey) != null) return false
        return dao.insert(transaction.toEntity()) != -1L
    }

    suspend fun retry(item: SmsSearchItem, currentRules: RuleConfig): Boolean {
        val transaction = SmsParser.parse(item.sender, item.body, item.receivedAt, currentRules).transaction ?: return false
        val saved = dao.findDuplicate(transaction.duplicateKey) ?: return false
        dao.updateStatus(saved.uniqueLocalId, "PENDING", "Manual resync requested")
        return true
    }
}
