package com.spendsense.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "category_rules")
data class CategoryRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchValue: String,
    val matchType: String = "MERCHANT",
    val category: String,
    val createdAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = true
)