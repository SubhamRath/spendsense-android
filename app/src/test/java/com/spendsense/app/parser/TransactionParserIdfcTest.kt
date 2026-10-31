package com.spendsense.app.parser

import com.spendsense.app.data.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionParserIdfcTest {
    private val sender = "IDFC FIRST Bank"

    @Test
    fun parsesStandardIdfcDebitAndIgnoresBalance() {
        val sms = "Your A/C XXXXX565983 is debited by INR 3,599.00 on 08/08/25 11:44. New Bal :INR 68,367.26. Call us on 180010888 for dispute. Team IDFC FIRST Bank"
        val tx = TransactionParser.parse(1L, sender, 0L, sms)

        assertEquals(TransactionType.DEBIT, tx?.type)
        assertEquals(3599.0, tx?.amount ?: -1.0, 0.001)
    }

    @Test
    fun parsesIdfcDebitWithRrnAndInvestmentMerchant() {
        val sms = "Your A/c XX5983 debited by Rs. 1,000.00 on 01/09/26; INDmoney credited. RRN 624407897074. Available balance Rs. 95,136.03. Team IDFC FIRST Bank"
        val tx = TransactionParser.parse(2L, sender, 0L, sms)

        assertEquals(TransactionType.DEBIT, tx?.type)
        assertEquals(1000.0, tx?.amount ?: -1.0, 0.001)
        assertEquals("INDmoney", tx?.merchant)
        assertEquals("624407897074", tx?.transactionRef)
        assertEquals("Investments", tx?.category)
    }

    @Test
    fun parsesIdfcPersonTransfer() {
        val sms = "Your A/c XX5983 debited by Rs. 1,200.00 on 23/09/26; SANGAT SINGH JATHOL credited. RRN 626601557558. Available balance Rs. 65,672.02. Team IDFC FIRST Bank"
        val tx = TransactionParser.parse(3L, sender, 0L, sms)

        assertEquals(TransactionType.DEBIT, tx?.type)
        assertEquals(1200.0, tx?.amount ?: -1.0, 0.001)
        assertEquals("SANGAT SINGH JATHOL", tx?.merchant)
        assertEquals("Transfers", tx?.category)
    }

    @Test
    fun parsesIdfcZerodhaNeftCredit() {
        val sms = "Your A/c XXXXXXX5983 has been credited with Rs. 4,799.38 on 17-10-2026. Info: NEFT/YESF362907838987/ZERODHA BROKING LTD-DSCNB A/C. New bal: Rs. 49,081.65. Team IDFC FIRST Bank"
        val tx = TransactionParser.parse(4L, sender, 0L, sms)

        assertEquals(TransactionType.CREDIT, tx?.type)
        assertEquals(4799.38, tx?.amount ?: -1.0, 0.001)
        assertTrue(tx?.merchant?.contains("ZERODHA BROKING LTD") == true)
        assertEquals("YESF362907838987", tx?.transactionRef)
        assertEquals("Investments", tx?.category)
    }

    @Test
    fun ignoresOtpMessageEvenWhenItContainsAnAmount() {
        val sms = "Your OTP is 482913 for a transaction of Rs. 3,599.00. Do not share this OTP. IDFC FIRST Bank"
        assertNull(TransactionParser.parse(7L, sender, 0L, sms))
    }

    @Test
    fun ignoresFailedPayment() {
        val sms = "Payment failed for Rs. 1,000.00 on your account. Transaction failed. IDFC FIRST Bank"
        assertNull(TransactionParser.parse(8L, sender, 0L, sms))
    }

    @Test
    fun ignoresDeclinedPayment() {
        val sms = "Transaction declined for Rs. 2,500.00. Please try again. IDFC FIRST Bank"
        assertNull(TransactionParser.parse(9L, sender, 0L, sms))
    }

    @Test
    fun ignoresCreditCardBillNotice() {
        val sms = "Your credit card bill of Rs. 12,000.00 is due on 10/11/2026. Total amount due. IDFC FIRST Bank"
        assertNull(TransactionParser.parse(10L, sender, 0L, sms))
    }

    @Test
    fun ignoresPromotionalSms() {
        val sms = "Congratulations! Get cashback offer of Rs. 500.00 on your next payment. IDFC FIRST Bank"
        assertNull(TransactionParser.parse(11L, sender, 0L, sms))
    }

    @Test
    fun parsesReversalAsCreditRefund() {
        val sms = "Rs. 1,000.00 has been reversed and credited to your account. IDFC FIRST Bank"
        val tx = TransactionParser.parse(12L, sender, 0L, sms)
        assertEquals(TransactionType.CREDIT, tx?.type)
        assertEquals("Refund", tx?.category)
    }

    @Test
    fun ignoresFutureMandateNotice() {
        val sms = "Your account will be debited with Rs 7,872.00 towards CRED CCBP for the MerchantMandate on 02/09/2026. Pause mandate to stop execution. IDFC FIRST Bank"
        assertNull(TransactionParser.parse(5L, sender, 0L, sms))
    }

    @Test
    fun ignoresFdCreationNoticeForNow() {
        val sms = "Your fixed deposit of Rs.1,00,000 has been created on 20-May-2026. For queries, call 1800-121-1905 - slice"
        assertNull(TransactionParser.parse(6L, "slice", 0L, sms))
    }
}
