package com.spendsense.app.parser

import com.spendsense.app.data.Transaction
import com.spendsense.app.data.TransactionType
import java.util.Locale

object TransactionParser {
    private val amountPatterns = listOf(
        Regex("""(?:INR|Rs\.?|₹)\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""(?:amount|amt)\s*(?:is|of|:)?\s*(?:INR|Rs\.?|₹)\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    )

    private val debitWords = Regex(
        """\b(debited|debit|spent|purchase|paid|payment|withdrawn|sent|transferred|charged|used)\b""",
        RegexOption.IGNORE_CASE
    )

    private val creditWords = Regex(
        """\b(credited|credit|received|deposited|salary|refund|cashback|reversed|reversal)\b""",
        RegexOption.IGNORE_CASE
    )

    private val financialWords = Regex(
        """\b(a/c|acct|account|upi|utr|rrn|imps|neft|rtgs|txn|transaction|ref(?:erence)?|vpa|bank|card|debited|credited|withdrawn|deposited)\b""",
        RegexOption.IGNORE_CASE
    )

    private val knownFinancialSenders = listOf(
        "HDFCBK", "HDFC", "SBIINB", "SBIBNK", "ICICIB", "ICICIBANK",
        "AXISBK", "AXISBANK", "KOTAKB", "KOTAK", "IDFCFB", "IDFC",
        "YESBK", "YESBANK", "INDUSB", "INDUSIND", "PNBSMS", "PNB",
        "BOIIND", "BOI", "CANBNK", "CANARA", "UBIBNK", "UNIONBK",
        "BARBOD", "BOB", "UCO", "UCOBANK", "PAYTM", "GPAY", "PHONEPE", "AMAZONPAY",
        "JUSPAY", "RAZORPAY", "CCAVENUE"
    )

    private val referencePatterns = listOf(
        Regex("""(?:UPI\s*(?:REF(?:ERENCE)?|TXN|TRANSACTION)?\s*(?:NO|NUMBER|ID)?|UPI\s*ID)\s*[:#-]?\s*([A-Za-z0-9./_-]{6,40})""", RegexOption.IGNORE_CASE),
        Regex("""(?:UTR|RRN|IMPS\s*(?:REF(?:ERENCE)?|NO|NUMBER)|NEFT\s*(?:REF(?:ERENCE)?|NO|NUMBER)|RTGS\s*(?:REF(?:ERENCE)?|NO|NUMBER))\s*[:#-]?\s*([A-Za-z0-9./_-]{6,40})""", RegexOption.IGNORE_CASE),
        Regex("""(?:TXN|TRANSACTION|REF(?:ERENCE)?|BANK\s*REF(?:ERENCE)?)\s*(?:NO|NUMBER|ID)?\s*[:#-]?\s*([A-Za-z0-9./_-]{6,40})""", RegexOption.IGNORE_CASE),
        Regex("""\bNEFT/([A-Za-z0-9]{8,30})/""", RegexOption.IGNORE_CASE)
    )

    private val merchantPatterns = listOf(
        Regex("""(?:to|at|for|towards|merchant)\s*[:\-]?\s*([A-Za-z0-9&._'@/-]{2,50})""", RegexOption.IGNORE_CASE),
        Regex("""\bby\s+(UCO-UPI|UCO|UCOBANK)\s*\.\s*(?:Avl|Available)\b""", RegexOption.IGNORE_CASE),
        Regex(""";\s*([^;\n]+?)\s+credited(?:\.|\s|$)""", RegexOption.IGNORE_CASE),
        Regex("""Info:\s*NEFT/[^/]+/([^\n]+?)(?:\.|\s+New\s+bal|$)""", RegexOption.IGNORE_CASE),
        Regex("""(?:VPA|UPI)\s*[:\-]?\s*([A-Za-z0-9._-]+@[A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:from|by|via)\s*[:\-]?\s*([A-Za-z0-9&._'@/-]{2,50})""", RegexOption.IGNORE_CASE)
    )

    fun parse(id: Long, sender: String, timestamp: Long, body: String): Transaction? {
        if (!isLikelyFinancialSms(sender, body)) return null

        val lower = body.lowercase(Locale.ROOT)
        if (isSafetyVeto(lower)) return null
        if (isUpcomingOrMandate(lower)) return null

        val amount = extractAmount(body) ?: return null

        val type = when {
            debitWords.containsMatchIn(lower) && !isPureCreditMessage(lower) -> TransactionType.DEBIT
            creditWords.containsMatchIn(lower) -> TransactionType.CREDIT
            else -> return null
        }

        val merchant = extractMerchant(body)
        val reference = extractReference(body)
        val category = categorize(body, merchant, type)
        val balance = extractAvailableBalance(body)

        val senderScore = senderScore(sender)
        val transactionScore = listOf(
            amount > 0,
            reference != null,
            merchant != "Unknown",
            financialWords.containsMatchIn(body),
            debitWords.containsMatchIn(lower) || creditWords.containsMatchIn(lower)
        ).count { it }

        val confidence = (senderScore + transactionScore * 10).coerceIn(0, 100)
        val dedupeKey = buildDedupeKey(sender, amount, type, merchant, reference, timestamp, balance, body)

        return Transaction(
            id = id,
            amount = amount,
            type = type,
            merchant = merchant,
            category = category,
            sender = sender,
            timestamp = timestamp,
            rawMessage = body,
            transactionRef = reference,
            dedupeKey = dedupeKey,
            confidence = confidence
        )
    }

    private fun extractAmount(body: String): Double? {
        amountPatterns.forEach { pattern ->
            pattern.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun extractAvailableBalance(body: String): Double? {
        val patterns = listOf(
            Regex("""(?:Avl\s*Bal|Available\s+balance|New\s+Bal|New\s+balance)\s*[:\-]?\s*(?:INR|Rs\.?|₹)\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
            Regex("""(?:Avl\s*Bal|Available\s+balance|New\s+Bal|New\s+balance)\s*[:\-]?\s*([0-9,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { pattern ->
            pattern.find(body)?.groupValues?.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun isSafetyVeto(lower: String): Boolean {
        val vetoSignals = listOf(
            "otp", "one time password", "verification code", "authentication code",
            "offer", "cashback offer", "limited time", "sale", "promo", "promotional",
            "win ", "winner", "congratulations",
            "transaction failed", "payment failed", "failed transaction",
            "transaction declined", "payment declined", "declined transaction",
            "transaction cancelled", "payment cancelled", "cancelled transaction",
            "transaction unsuccessful", "payment unsuccessful",
            "credit card bill", "card bill", "bill payment due", "minimum amount due",
            "total amount due", "statement generated"
        )
        return vetoSignals.any { it in lower }
    }

    private fun isUpcomingOrMandate(lower: String): Boolean {
        val futureSignals = listOf(
            "will be debited",
            "will be credited",
            "to be debited",
            "to be credited",
            "scheduled to be debited",
            "scheduled debit",
            "upcoming debit",
            "pause mandate",
            "merchantmandate",
            "upi mandate"
        )
        return futureSignals.any { it in lower }
    }

    private fun isLikelyFinancialSms(sender: String, body: String): Boolean {
        val normalizedSender = sender.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
        val knownSender = knownFinancialSenders.any { normalizedSender.contains(it) }
        val strongFinancialSignals = listOf(
            referencePatterns.any { it.containsMatchIn(body) },
            Regex("""\b(?:A/C|ACCT|ACCOUNT|UPI|VPA|UTR|RRN|IMPS|NEFT|RTGS|DEBITED|CREDITED)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(body)
        ).count { it }

        return knownSender || strongFinancialSignals >= 1
    }

    private fun senderScore(sender: String): Int {
        val normalizedSender = sender.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
        return if (knownFinancialSenders.any { normalizedSender.contains(it) }) 40 else 15
    }

    private fun isPureCreditMessage(lower: String): Boolean {
        return creditWords.containsMatchIn(lower) && !debitWords.containsMatchIn(lower)
    }

    private fun extractReference(body: String): String? {
        referencePatterns.forEach { pattern ->
            pattern.find(body)?.groupValues?.getOrNull(1)?.trim()?.let {
                if (it.length >= 6) return it
            }
        }
        return null
    }

    private fun extractMerchant(body: String): String {
        merchantPatterns.forEach { pattern ->
            pattern.find(body)?.groupValues?.getOrNull(1)?.let {
                val cleaned = it.trim().trimEnd('.', ',', ';', ':')
                val normalized = cleaned.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
                if (normalized in setOf("UCOUPI", "UCO", "UCOBANK")) return "Unknown"
                if (cleaned.length >= 2) return cleaned
            }
        }
        return "Unknown"
    }

    private fun buildDedupeKey(
        sender: String,
        amount: Double,
        type: TransactionType,
        merchant: String,
        reference: String?,
        timestamp: Long,
        balance: Double?,
        body: String
    ): String {
        if (!reference.isNullOrBlank()) {
            return "ref:" + reference.uppercase(Locale.ROOT).trim()
        }

        val normalizedMerchant = merchant.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        val normalizedBody = body.lowercase(Locale.ROOT)
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val balancePart = balance?.let { "%.2f".format(Locale.US, it) } ?: "nobal"
        return "fp:" + type.name + ":" + "%.2f".format(Locale.US, amount) + ":" +
            normalizedMerchant + ":" + balancePart + ":" + normalizedBody
    }

    private fun categorize(body: String, merchant: String, type: TransactionType): String {
        val text = "$body $merchant".lowercase(Locale.ROOT)

        if (type == TransactionType.CREDIT) return when {
            listOf("salary", "payroll", "salary credit").any { it in text } -> "Salary"
            listOf("refund", "reversal", "reverted", "reversed").any { it in text } -> "Refund"
            "cashback" in text -> "Cashback"
            listOf("zerodha", "groww", "indmoney", "upstox", "angel one", "broker").any { it in text } -> "Investments"
            else -> "Other income"
        }

        return when {
            listOf("swiggy", "zomato", "restaurant", "food", "cafe", "domino", "pizza", "burger").any { it in text } -> "Food"
            listOf("blinkit", "zepto", "instamart", "bigbasket", "jiomart", "grofers", "dmart", "supermarket", "grocery", "groceries", "quick commerce").any { it in text } -> "Groceries"
            listOf("amazon", "flipkart", "myntra", "ajio", "shopping").any { it in text } -> "Shopping"
            listOf("petrol", "fuel", "hpcl", "bpcl", "iocl", "bharat petroleum", "indian oil").any { it in text } -> "Fuel"
            listOf("uber", "ola", "rapido", "metro", "irctc", "redbus").any { it in text } -> "Transport"
            listOf("electricity", "water bill", "broadband", "airtel", "jio", "vi ", "recharge", "dth").any { it in text } -> "Bills"
            listOf("sip", "mutual fund", "groww", "zerodha", "coin", "investment", "nps", "indmoney", "brokerage", "broking").any { it in text } -> "Investments"
            listOf("atm", "cash withdrawal", "cash withdrawn").any { it in text } -> "Cash withdrawal"
            listOf("transfer", "credited", "rrn").any { it in text } && merchant != "Unknown" -> "Transfers"
            else -> "Other"
        }
    }
}