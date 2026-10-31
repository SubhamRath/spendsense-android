package com.spendsense.app.sms

import android.content.Context
import android.provider.Telephony
import com.spendsense.app.data.Transaction
import com.spendsense.app.parser.TransactionParser

object SmsScanner {
    fun scan(context: Context): List<Transaction> {
        val parsed = mutableListOf<Transaction>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )

        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (cursor.moveToNext()) {
                TransactionParser.parse(
                    id = cursor.getLong(idIndex),
                    sender = cursor.getString(addressIndex).orEmpty(),
                    timestamp = cursor.getLong(dateIndex),
                    body = cursor.getString(bodyIndex).orEmpty()
                )?.let(parsed::add)
            }
        }

        return deduplicate(parsed)
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