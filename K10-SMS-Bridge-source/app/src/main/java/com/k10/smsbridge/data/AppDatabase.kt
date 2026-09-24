package com.k10.smsbridge.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "transactions",
    indices = [Index(value = ["duplicateKey"], unique = true)]
)
data class TransactionEntity(
    @PrimaryKey val uniqueLocalId: String,
    val duplicateKey: String,
    val payerName: String,
    val amountMinor: Long,
    val currency: String = "INR",
    val transactionDate: String,
    val smsReceivedTimestamp: Long,
    val accountLast4: String,
    val paymentMethod: String,
    val senderId: String,
    val rawEligibleSms: String,
    val source: String = "slice_sms",
    val syncStatus: String = "PENDING",
    val serverMessage: String? = null,
    val serverTransactionId: String? = null,
    val lastHttpCode: Int? = null,
    val uploadAttemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val statusUpdatedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: TransactionEntity): Long

    @Query("SELECT * FROM transactions ORDER BY smsReceivedTimestamp DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE duplicateKey = :key LIMIT 1")
    suspend fun findDuplicate(key: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE syncStatus IN ('PENDING','FAILED','UPLOADING') ORDER BY createdAt")
    suspend fun pending(): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY createdAt")
    suspend fun allForServerCheck(): List<TransactionEntity>

    @Query("UPDATE transactions SET syncStatus = :status, serverMessage = :message, statusUpdatedAt = :updatedAt WHERE uniqueLocalId = :id")
    suspend fun updateStatus(id: String, status: String, message: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE transactions SET syncStatus = 'UPLOADING', serverMessage = 'Uploading to server', uploadAttemptCount = uploadAttemptCount + 1, lastAttemptAt = :attemptedAt, statusUpdatedAt = :attemptedAt WHERE uniqueLocalId = :id")
    suspend fun markUploading(id: String, attemptedAt: Long = System.currentTimeMillis())

    @Query("UPDATE transactions SET syncStatus = :status, serverMessage = :message, serverTransactionId = COALESCE(:serverId, serverTransactionId), lastHttpCode = :httpCode, statusUpdatedAt = :updatedAt WHERE uniqueLocalId = :id")
    suspend fun updateUploadResult(
        id: String,
        status: String,
        message: String?,
        serverId: String?,
        httpCode: Int?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM transactions WHERE uniqueLocalId = :id LIMIT 1")
    suspend fun findByLocalId(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions ORDER BY smsReceivedTimestamp DESC")
    suspend fun snapshot(): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE syncStatus = :status")
    fun observeCount(status: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM transactions WHERE smsReceivedTimestamp >= :since")
    fun observeDetectedSince(since: Long): Flow<Int>
}

@Database(entities = [TransactionEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN serverTransactionId TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN lastHttpCode INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN uploadAttemptCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN lastAttemptAt INTEGER")
            }
        }

        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "k10_sms_bridge.db"
        ).addMigrations(MIGRATION_1_2).build()
    }
}
