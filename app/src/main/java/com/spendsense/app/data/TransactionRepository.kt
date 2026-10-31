package com.spendsense.app.data

import android.content.Context
import com.spendsense.app.sms.SmsScanner\nimport com.spendsense.app.parser.TransactionParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class TransactionRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val db = SpendSenseDatabase.get(appContext)
    private val transactions = db.transactionDao()
    private val categories = db.categoryDao()
    private val rules = db.categoryRuleDao()
    private val scanState = appContext.getSharedPreferences("sms_scan_state", Context.MODE_PRIVATE)
    private val lastScannedTimestampKey = "last_scanned_sms_timestamp"

    suspend fun seedDefaultCategories() {
        CategoryDefaults.names.forEach { categories.insert(CategoryEntity(it, false)) }
    }

    fun observeTransactions(): Flow<List<TransactionEntity>> = transactions.observeAll()
    fun observeCategories(): Flow<List<CategoryEntity>> = categories.observeAll()

    suspend fun processIncomingSms(
        id: Long,
        sender: String,
        timestamp: Long,
        body: String
    ) {
        val transaction = TransactionParser.parse(
            id = id,
            sender = sender,
            timestamp = timestamp,
            body = body
        ) ?: return

        val matchingRule = rules.observeEnabled().first().firstOrNull { rule ->
            when (rule.matchType) {
                "VPA" -> transaction.rawMessage.contains(rule.matchValue, ignoreCase = true)
                else -> transaction.merchant.equals(rule.matchValue, ignoreCase = true)
            }
        }

        val categorized = matchingRule?.let { transaction.copy(category = it.category) } ?: transaction
        transactions.upsertAll(listOf(categorized.toEntity()))
    }

    suspend fun scanAndPersist(context: Context) {
        val currentRules = rules.observeEnabled().first()
        val lastScannedTimestamp = scanState.getLong(lastScannedTimestampKey, 0L).takeIf { it > 0L }
        val scanResult = SmsScanner.scan(context, lastScannedTimestamp)
        val scanned = scanResult.transactions.map { transaction ->
            val matchingRule = currentRules.firstOrNull { rule ->
                when (rule.matchType) {
                    "VPA" -> transaction.rawMessage.contains(rule.matchValue, ignoreCase = true)
                    else -> transaction.merchant.equals(rule.matchValue, ignoreCase = true)
                }
            }
            if (matchingRule != null) transaction.copy(category = matchingRule.category)
            else transaction
        }
        transactions.upsertAll(scanned.map { it.toEntity() })

        scanResult.newestSmsTimestamp?.let { newest ->
            scanState.edit().putLong(lastScannedTimestampKey, newest).apply()
        }
    }

    suspend fun setCategory(smsId: Long, category: String) {
        transactions.updateCategory(smsId, category)
    }

    suspend fun setCategoryForMerchant(merchant: String, category: String) {
        if (merchant.isBlank() || merchant == "Unknown") return
        rules.insert(CategoryRule(matchValue = merchant.trim(), category = category))
        transactions.applyCategoryToMerchant(merchant, category)
    }

    suspend fun addCustomCategory(name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) categories.insert(CategoryEntity(clean, true))
    }

    companion object {
        @Volatile private var INSTANCE: TransactionRepository? = null
        fun get(context: Context): TransactionRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransactionRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}