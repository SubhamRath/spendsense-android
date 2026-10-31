package com.spendsense.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    indices = [Index(value = ["dedupeKey"], unique = true)]
)
data class TransactionEntity(
    @PrimaryKey val smsId: Long,
    val amount: Double,
    val type: String,
    val merchant: String,
    val category: String,
    val sender: String,
    val timestamp: Long,
    val rawMessage: String,
    val transactionRef: String?,
    val dedupeKey: String,
    val confidence: Int
)

fun Transaction.toEntity() = TransactionEntity(
    smsId = id,
    amount = amount,
    type = type.name,
    merchant = merchant,
    category = category,
    sender = sender,
    timestamp = timestamp,
    rawMessage = rawMessage,
    transactionRef = transactionRef,
    dedupeKey = dedupeKey,
    confidence = confidence
)

fun TransactionEntity.toTransaction() = Transaction(
    id = smsId,
    amount = amount,
    type = TransactionType.valueOf(type),
    merchant = merchant,
    category = category,
    sender = sender,
    timestamp = timestamp,
    rawMessage = rawMessage,
    transactionRef = transactionRef,
    dedupeKey = dedupeKey,
    confidence = confidence
)