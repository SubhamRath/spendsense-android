package com.spendsense.app.parser

import com.spendsense.app.data.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TransactionParserUcoTest {
    private val sender = "UCO Bank"

    @Test
    fun parsesUcoDebitAndIgnoresAvailableBalance() {
        val sms = "A/c XX8170 Debited with Rs.1,065.00 on 23-09-2026 by UCO-UPI.Avl Bal Rs.5118.53. Report Dispute https://spgrs.ucoonline.bank.in/Home_Page.jsp"
        val tx = TransactionParser.parse(101L, sender, 0L, sms)

        assertNotNull(tx)
        assertEquals(TransactionType.DEBIT, tx?.type)
        assertEquals(1065.0, tx?.amount ?: -1.0, 0.001)
        assertEquals("Unknown", tx?.merchant)
        assertEquals("Other", tx?.category)
    }

    @Test
    fun parsesUcoCredit() {
        val sms = "A/c XX8170 Credited with Rs.5,000.00 on 23-10-2026 by UCO-UPI.Avl Bal Rs.13911.93. Report Dispute https://spgrs.ucoonline.bank.in/Home_Page.jsp -UCO Bank"
        val tx = TransactionParser.parse(102L, sender, 0L, sms)

        assertNotNull(tx)
        assertEquals(TransactionType.CREDIT, tx?.type)
        assertEquals(5000.0, tx?.amount ?: -1.0, 0.001)
        assertEquals("Unknown", tx?.merchant)
    }

    @Test
    fun sameUcoMessageWithDifferentSmsIdsProducesSameFallbackDedupeKey() {
        val sms = "A/c XX8170 Debited with Rs.1,065.00 on 23-09-2026 by UCO-UPI.Avl Bal Rs.5118.53. Report Dispute https://spgrs.ucoonline.bank.in/Home_Page.jsp"
        val first = TransactionParser.parse(103L, sender, 1_760_000_000_000L, sms)
        val duplicate = TransactionParser.parse(104L, sender, 1_760_000_030_000L, sms)

        assertNotNull(first)
        assertNotNull(duplicate)
        assertEquals(first?.dedupeKey, duplicate?.dedupeKey)
    }

    @Test
    fun sameAmountDifferentAvailableBalanceIsNotDeduped() {
        val sms1 = "A/c XX8170 Debited with Rs.500.00 on 28-10-2026 by UCO-UPI.Avl Bal Rs.12883.93. Report Dispute"
        val sms2 = "A/c XX8170 Debited with Rs.500.00 on 28-10-2026 by UCO-UPI.Avl Bal Rs.12383.93. Report Dispute"

        val first = TransactionParser.parse(105L, sender, 1_760_000_000_000L, sms1)
        val second = TransactionParser.parse(106L, sender, 1_760_000_001_000L, sms2)

        assertNotNull(first)
        assertNotNull(second)
        org.junit.Assert.assertNotEquals(first?.dedupeKey, second?.dedupeKey)
    }
}
