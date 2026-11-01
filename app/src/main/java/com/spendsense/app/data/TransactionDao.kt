package com.spendsense.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<TransactionEntity>)

    @Query("UPDATE transactions SET category = :category WHERE merchant = :merchant")
    suspend fun applyCategoryToMerchant(merchant: String, category: String)

    @Query("UPDATE transactions SET category = :category WHERE smsId = :smsId")
    suspend fun updateCategory(smsId: Long, category: String)

    @Query("UPDATE transactions SET sender = :bank WHERE smsId = :smsId")
    suspend fun updateBank(smsId: Long, bank: String)

    @Query("UPDATE transactions SET amount = :amount, type = :type, merchant = :merchant, timestamp = :timestamp WHERE smsId = :smsId")
    suspend fun updateTransaction(smsId: Long, amount: Double, type: String, merchant: String, timestamp: Long)

    @Query("DELETE FROM transactions WHERE smsId = :smsId")
    suspend fun deleteTransaction(smsId: Long)
}