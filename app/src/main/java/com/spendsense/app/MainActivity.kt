package com.spendsense.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.spendsense.app.data.Transaction
import com.spendsense.app.data.TransactionType
import com.spendsense.app.sms.SmsScanner
import java.text.NumberFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var transactions by mutableStateOf<List<Transaction>>(emptyList())
    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) scanSms()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Dashboard(transactions, ::requestSmsPermission)
                }
            }
        }
    }

    private fun requestSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED) scanSms()
        else smsPermissionLauncher.launch(Manifest.permission.READ_SMS)
    }

    private fun scanSms() {
        transactions = SmsScanner.scan(this)
    }
}

@Composable
private fun Dashboard(transactions: List<Transaction>, onScan: () -> Unit) {
    val income = transactions.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount }
    val expenses = transactions.filter { it.type == TransactionType.DEBIT }.sumOf { it.amount }
    val currency = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("SpendSense", style = MaterialTheme.typography.headlineMedium)
        Text("SMS transaction scanner", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onScan, Modifier.fillMaxWidth()) { Text("Scan transaction SMS") }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard("Income", currency.format(income), Modifier.weight(1f))
            SummaryCard("Expenses", currency.format(expenses), Modifier.weight(1f))
        }
        SummaryCard("Net cash flow", currency.format(income - expenses), Modifier.fillMaxWidth())
        Text("Detected transactions (${transactions.size})", style = MaterialTheme.typography.titleLarge)

        if (transactions.isEmpty()) {
            Text("No transactions scanned yet. Tap the button above and grant SMS access.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(transactions.take(100), key = { it.id }) { TransactionRow(it, currency) }
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun TransactionRow(transaction: Transaction, currency: NumberFormat) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(transaction.merchant, style = MaterialTheme.typography.titleMedium)
            Text(transaction.category, style = MaterialTheme.typography.labelMedium)
            Text(
                (if (transaction.type == TransactionType.CREDIT) "+ " else "- ") +
                    currency.format(transaction.amount)
            )
        }
    }
}
