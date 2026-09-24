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

    @Query("SELECT * FROM transactions WHERE syncStatus IN ('PENDING','FAILED') ORDER BY createdAt")
    suspend fun pending(): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY createdAt")
    suspend fun allForServerCheck(): List<TransactionEntity>

    @Query("UPDATE transactions SET syncStatus = :status, serverMessage = :message, statusUpdatedAt = :updatedAt WHERE uniqueLocalId = :id")
    suspend fun updateStatus(id: String, status: String, message: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM transactions WHERE syncStatus = :status")
    fun observeCount(status: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM transactions WHERE smsReceivedTimestamp >= :since")
    fun observeDetectedSince(since: Long): Flow<Int>
}

@Database(entities = [TransactionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "k10_sms_bridge.db"
        ).build()
    }
}
