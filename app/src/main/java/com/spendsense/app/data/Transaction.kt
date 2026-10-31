package com.spendsense.app.data

enum class TransactionType { CREDIT, DEBIT }

data class Transaction(
    val id: Long,
    val amount: Double,
    val type: TransactionType,
    val merchant: String,
    val category: String,
    val sender: String,
    val timestamp: Long,
    val rawMessage: String,
    val transactionRef: String? = null,
    val dedupeKey: String = "sms:$id",
    val confidence: Int = 0
)