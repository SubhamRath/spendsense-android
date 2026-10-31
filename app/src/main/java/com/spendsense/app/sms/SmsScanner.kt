package com.spendsense.app.sms

import android.content.Context
import android.provider.Telephony
import com.spendsense.app.data.Transaction
import com.spendsense.app.parser.TransactionParser

object SmsScanner {
    data class ScanResult(
        val transactions: List<Transaction>,
        val newestSmsTimestamp: Long?
    )

    fun scan(context: Context, sinceTimestamp: Long? = null): ScanResult {
        val parsed = mutableListOf<Transaction>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )

        var newestSmsTimestamp: Long? = null

        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            sinceTimestamp?.let { "${Telephony.Sms.DATE} > ?" },
            sinceTimestamp?.let { arrayOf(it.toString()) },
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (cursor.moveToNext()) {
                val smsTimestamp = cursor.getLong(dateIndex)
                if (newestSmsTimestamp == null || smsTimestamp > newestSmsTimestamp!!) {
                    newestSmsTimestamp = smsTimestamp
                }
                TransactionParser.parse(
                    id = cursor.getLong(idIndex),
                    sender = cursor.getString(addressIndex).orEmpty(),
                    timestamp = smsTimestamp,
                    body = cursor.getString(bodyIndex).orEmpty()
                )?.let(parsed::add)
            }
        }

        return ScanResult(
            transactions = deduplicate(parsed),
            newestSmsTimestamp = newestSmsTimestamp
        )
    }

    private fun deduplicate(transactions: List<Transaction>): List<Transaction> {
        val seen = mutableMapOf<String, Transaction>()

        for (transaction in transactions.sortedByDescending { it.timestamp }) {
            val existing = seen[transaction.dedupeKey]
            if (existing == null) {
                seen[transaction.dedupeKey] = transaction
            } else if (
                transaction.confidence > existing.confidence ||
                (transaction.confidence == existing.confidence && transaction.timestamp > existing.timestamp)
            ) {
                seen[transaction.dedupeKey] = transaction
            }
        }

        return seen.values.sortedByDescending { it.timestamp }
    }
}