package com.spendsense.app.sms

import android.content.Context
import android.provider.Telephony
import com.spendsense.app.data.Transaction
import com.spendsense.app.parser.TransactionParser

object SmsScanner {
    fun scan(context: Context): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, projection, null, null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext()) {
                TransactionParser.parse(
                    cursor.getLong(idIndex),
                    cursor.getString(addressIndex).orEmpty(),
                    cursor.getLong(dateIndex),
                    cursor.getString(bodyIndex).orEmpty()
                )?.let(transactions::add)
            }
        }
        return transactions
    }
}