package com.spendsense.app.parser

import com.spendsense.app.data.Transaction
import com.spendsense.app.data.TransactionType
import java.util.Locale

object TransactionParser {
    private val amountRegex = Regex("""(?:INR|Rs\.?|₹)\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    private val debitWords = Regex("""\b(debited|debit|spent|purchase|paid|payment|withdrawn|sent|transferred)\b""", RegexOption.IGNORE_CASE)
    private val creditWords = Regex("""\b(credited|credit|received|deposited|salary|refund|cashback)\b""", RegexOption.IGNORE_CASE)

    fun parse(id: Long, sender: String, timestamp: Long, body: String): Transaction? {
        val amount = amountRegex.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        val lower = body.lowercase(Locale.ROOT)
        val type = when {
            debitWords.containsMatchIn(lower) -> TransactionType.DEBIT
            creditWords.containsMatchIn(lower) -> TransactionType.CREDIT
            else -> return null
        }
        val merchant = extractMerchant(body, type)
        return Transaction(id, amount, type, merchant, categorize(body, merchant, type), sender, timestamp, body)
    }

    private fun extractMerchant(body: String, type: TransactionType): String {
        val patterns = when (type) {
            TransactionType.DEBIT -> listOf(
                Regex("""(?:to|at|for|towards)\s+([A-Za-z0-9&._' -]{2,40})""", RegexOption.IGNORE_CASE),
                Regex("""VPA\s+([A-Za-z0-9._-]+@[A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
            )
            TransactionType.CREDIT -> listOf(
                Regex("""(?:from|by|via)\s+([A-Za-z0-9&._' -]{2,40})""", RegexOption.IGNORE_CASE)
            )
        }
        patterns.forEach { pattern -> pattern.find(body)?.let { return it.groupValues[1].trim().trimEnd('.', ',') } }
        return "Unknown"
    }

    private fun categorize(body: String, merchant: String, type: TransactionType): String {
        val text = "$body $merchant".lowercase(Locale.ROOT)
        if (type == TransactionType.CREDIT) return when {
            "salary" in text || "payroll" in text -> "Salary"
            "refund" in text || "reversal" in text -> "Refund"
            "cashback" in text -> "Cashback"
            else -> "Other income"
        }
        return when {
            listOf("swiggy", "zomato", "restaurant", "food", "cafe", "domino").any { it in text } -> "Food"
            listOf("amazon", "flipkart", "myntra", "ajio", "shopping").any { it in text } -> "Shopping"
            listOf("petrol", "fuel", "hpcl", "bpcl", "iocl", "bharat petroleum").any { it in text } -> "Fuel"
            listOf("uber", "ola", "rapido", "metro", "irctc").any { it in text } -> "Transport"
            listOf("electricity", "water bill", "broadband", "airtel", "jio", "vi ").any { it in text } -> "Bills"
            listOf("sip", "mutual fund", "groww", "zerodha", "coin", "investment").any { it in text } -> "Investments"
            listOf("atm", "cash withdrawal").any { it in text } -> "Cash withdrawal"
            else -> "Other"
        }
    }
}