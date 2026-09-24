package com.k10.smsbridge.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.getWorkInfoByIdFlow
import androidx.work.workDataOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

data class ConfirmedSyncResult(
    val successful: Boolean,
    val locallyAdded: Int,
    val checked: Int,
    val synced: Int,
    val alreadyOnServer: Int,
    val excluded: Int,
    val failed: Int,
    val error: String?
) {
    fun userMessage(): String = when {
        successful -> "Local SMS and server transactions synced"
        !error.isNullOrBlank() -> error
        else -> "$failed transaction(s) could not sync. Check connection and API settings."
    }
}

object ConfirmedSync {
    suspend fun run(context: Context, scanFullHistory: Boolean, recheckAll: Boolean): ConfirmedSyncResult {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(
                SyncWorker.KEY_INTERACTIVE to true,
                SyncWorker.KEY_FULL_HISTORY to scanFullHistory,
                SyncWorker.KEY_RECHECK_ALL to recheckAll
            ))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(SyncWorker.USER_SYNC_NAME, ExistingWorkPolicy.REPLACE, request)
        val info = workManager.getWorkInfoByIdFlow(request.id).filterNotNull().first { it.state.isFinished }
        val data = info.outputData
        return ConfirmedSyncResult(
            successful = info.state == WorkInfo.State.SUCCEEDED,
            locallyAdded = data.getInt(SyncWorker.OUTPUT_SCANNED, 0),
            checked = data.getInt(SyncWorker.OUTPUT_ATTEMPTED, 0),
            synced = data.getInt(SyncWorker.OUTPUT_SYNCED, 0),
            alreadyOnServer = data.getInt(SyncWorker.OUTPUT_DUPLICATES, 0),
            excluded = data.getInt(SyncWorker.OUTPUT_EXCLUDED, 0),
            failed = data.getInt(SyncWorker.OUTPUT_FAILED, 0),
            error = data.getString(SyncWorker.OUTPUT_ERROR)
        )
    }
}
