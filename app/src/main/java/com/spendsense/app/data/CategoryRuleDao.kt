package com.spendsense.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryRuleDao {
    @Query("SELECT * FROM category_rules WHERE enabled = 1 ORDER BY createdAt DESC")
    fun observeEnabled(): Flow<List<CategoryRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: CategoryRule)

    @Query("DELETE FROM category_rules WHERE id = :id")
    suspend fun delete(id: Long)
}