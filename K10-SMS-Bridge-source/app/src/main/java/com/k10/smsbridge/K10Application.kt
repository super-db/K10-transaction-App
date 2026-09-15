package com.k10.smsbridge

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.k10.smsbridge.data.AppDatabase
import com.k10.smsbridge.rules.RuleStore
import com.k10.smsbridge.security.SecureTokenStore
import com.k10.smsbridge.sync.SyncWorker
import com.k10.smsbridge.voice.TransactionAnnouncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.TimeUnit

class K10Application : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

object Graph {
    lateinit var database: AppDatabase
    lateinit var rules: RuleStore
    lateinit var tokenStore: SecureTokenStore
    lateinit var announcer: TransactionAnnouncer
    val appScope = CoroutineScope(SupervisorJob())

    fun init(app: Application) {
        database = AppDatabase.create(app)
        rules = RuleStore(app)
        tokenStore = SecureTokenStore(app)
        announcer = TransactionAnnouncer(app)
    }
}
