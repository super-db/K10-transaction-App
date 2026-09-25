package com.k10.smsbridge.diagnostics

import com.k10.smsbridge.Graph
import com.k10.smsbridge.data.DiagnosticEventLogEntity

object DiagnosticEventLog {
    suspend fun info(code: String, stage: String, message: String, transactionLocalId: String? = null, referenceId: String? = null) =
        record("INFO", code, stage, message, transactionLocalId, referenceId)

    suspend fun warning(code: String, stage: String, message: String, transactionLocalId: String? = null, referenceId: String? = null) =
        record("WARNING", code, stage, message, transactionLocalId, referenceId)

    suspend fun error(code: String, stage: String, message: String, transactionLocalId: String? = null, referenceId: String? = null) =
        record("ERROR", code, stage, message, transactionLocalId, referenceId)

    private suspend fun record(level: String, code: String, stage: String, message: String, transactionLocalId: String?, referenceId: String?) {
        runCatching {
            val dao = Graph.database.diagnosticLogs()
            dao.insert(
                DiagnosticEventLogEntity(
                    level = level,
                    code = code.take(80),
                    stage = stage.take(40),
                    message = message.replace(Regex("[\\r\\n]+"), " ").take(300),
                    transactionLocalId = transactionLocalId?.take(128),
                    referenceId = referenceId?.take(128)
                )
            )
            dao.trimToLatest()
        }
    }
}
