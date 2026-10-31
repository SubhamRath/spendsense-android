package com.spendsense.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TransactionEntity::class, CategoryEntity::class, CategoryRule::class],
    version = 1,
    exportSchema = false
)
abstract class SpendSenseDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun categoryRuleDao(): CategoryRuleDao

    companion object {
        @Volatile private var INSTANCE: SpendSenseDatabase? = null

        fun get(context: Context): SpendSenseDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SpendSenseDatabase::class.java,
                    "spendsense.db"
                ).build().also { INSTANCE = it }
            }
    }
}