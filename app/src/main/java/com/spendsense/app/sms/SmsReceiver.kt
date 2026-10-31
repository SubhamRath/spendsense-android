package com.spendsense.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.spendsense.app.data.TransactionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = TransactionRepository.get(appContext)
                Telephony.Sms.Intents.getMessagesFromIntent(intent).forEach { message ->
                    val body = message.messageBody.orEmpty()
                    if (body.isBlank()) return@forEach

                    repository.processIncomingSms(
                        id = stableIncomingSmsId(
                            sender = message.originatingAddress.orEmpty(),
                            timestamp = message.timestampMillis,
                            body = body
                        ),
                        sender = message.originatingAddress.orEmpty(),
                        timestamp = message.timestampMillis,
                        body = body
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun stableIncomingSmsId(sender: String, timestamp: Long, body: String): Long {
        val input = "$sender|$timestamp|$body".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        var value = 0L
        repeat(8) { index ->
            value = (value shl 8) or (digest[index].toLong() and 0xffL)
        }
        return value or Long.MIN_VALUE
    }
}
