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
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import java.util.Calendar
import androidx.core.content.ContextCompat
import com.spendsense.app.data.Transaction
import com.spendsense.app.data.TransactionType
import com.spendsense.app.data.CategoryEntity
import com.spendsense.app.data.TransactionEntity
import com.spendsense.app.data.TransactionRepository
import com.spendsense.app.data.toTransaction
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private enum class Period(val label: String) { TODAY("Today"), WEEK("Week"), MONTH("Month"), YEAR("Year"), ALL("All time") }

class MainActivity : ComponentActivity() {
    private val repository by lazy { TransactionRepository.get(this) }
    private var transactions by mutableStateOf<List<Transaction>>(emptyList())
    private var categories by mutableStateOf<List<CategoryEntity>>(emptyList())
    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.READ_SMS] == true) scanSms()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repository.seedDefaultCategories()
            repository.observeTransactions().collect { transactions = it.map(TransactionEntity::toTransaction) }
        }
        lifecycleScope.launch {
            repository.observeCategories().collect { categories = it }
        }

        setContent {
            SpendSenseTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Dashboard(
                        transactions = transactions,
                        categories = categories.map { it.name },
                        onScan = ::requestSmsPermission,
                        onChangeCategory = { transaction, category, future ->
                            lifecycleScope.launch {
                                if (future) repository.setCategoryForMerchant(transaction.merchant, category)
                                else repository.setCategory(transaction.id, category)
                            }
                        },
                        onAddCategory = { name ->
                            lifecycleScope.launch { repository.addCustomCategory(name) }
                        }
                    )
                }
            }
        }
    }

    private fun requestSmsPermission() {
        val readGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val receiveGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

        if (readGranted && receiveGranted) {
            scanSms()
        } else {
            smsPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_SMS,
                    Manifest.permission.RECEIVE_SMS
                )
            )
        }
    }

    private fun scanSms() {
        lifecycleScope.launch { repository.scanAndPersist(this@MainActivity) }
    }
}

@Composable
private fun Dashboard(
    transactions: List<Transaction>,
    categories: List<String>,
    onScan: () -> Unit,
    onChangeCategory: (Transaction, String, Boolean) -> Unit,
    onAddCategory: (String) -> Unit
) {
    var selectedPeriod by remember { mutableStateOf(Period.MONTH) }
    val filtered = remember(transactions, selectedPeriod) { transactions.filterBy(selectedPeriod) }
    val income = filtered.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount }
    val expenses = filtered.filter { it.type == TransactionType.DEBIT && it.category != "Transfers" }.sumOf { it.amount }
    val transfers = filtered.filter { it.type == TransactionType.DEBIT && it.category == "Transfers" }.sumOf { it.amount }
    val currency = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("SpendSense", style = MaterialTheme.typography.headlineMedium)
                Text("Your money at a glance", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onScan) { Text("Scan SMS") }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Period.values().forEach { period ->
                FilterChip(selected = selectedPeriod == period, onClick = { selectedPeriod = period }, label = { Text(period.label) })
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Earned", currency.format(income), Modifier.weight(1f))
                    MetricCard("Spent", currency.format(expenses), Modifier.weight(1f))
                }
            }
            item { MetricCard("Net cash flow", currency.format(income - expenses), Modifier.fillMaxWidth()) }
            item {
                Text("Spending by category", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                val breakdown = filtered.filter { it.type == TransactionType.DEBIT && it.category != "Transfers" }
                    .groupBy { it.category }.mapValues { (_, items) -> items.sumOf { it.amount } }
                    .toList().sortedByDescending { it.second }.take(6)
                if (breakdown.isEmpty()) Text("No spending recorded for this period.")
                else breakdown.forEach { (category, amount) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(category)
                        Text(currency.format(amount))
                    }
                }
                if (transfers > 0) Text("Transfers excluded from spending: " + currency.format(transfers), style = MaterialTheme.typography.bodySmall)
            }
            item { Text("Transactions (" + filtered.size + ")", style = MaterialTheme.typography.titleLarge) }
            if (filtered.isEmpty()) item { Text("No transactions in this period.") }
            else items(filtered.take(100), key = { it.id }) { TransactionRow(it, currency, categories, onChangeCategory, onAddCategory) }
        }
    }
    }
}

@Composable
private fun SpendSenseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF8AB4F8), background = androidx.compose.ui.graphics.Color(0xFF0B0F14), surface = androidx.compose.ui.graphics.Color(0xFF151A21), surfaceVariant = androidx.compose.ui.graphics.Color(0xFF20262E)), content = content)
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun TransactionRow(
    transaction: Transaction,
    currency: NumberFormat,
    categories: List<String>,
    onChangeCategory: (Transaction, String, Boolean) -> Unit,
    onAddCategory: (String) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmExpanded by remember { mutableStateOf(false) }
    var addCategoryExpanded by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(transaction.category) }
    var newCategory by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(transaction.merchant, style = MaterialTheme.typography.titleMedium)
            Text(transaction.category, style = MaterialTheme.typography.labelMedium)
            Text(
                (if (transaction.type == TransactionType.CREDIT) "+ " else "- ") +
                    currency.format(transaction.amount)
            )
            TextButton(onClick = { menuExpanded = true }) { Text("Change category") }

            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                categories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category) },
                        onClick = {
                            selectedCategory = category
                            menuExpanded = false
                            confirmExpanded = true
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("+ Add new category") },
                    onClick = {
                        menuExpanded = false
                        addCategoryExpanded = true
                    }
                )
            }

            if (confirmExpanded) {
                AlertDialog(
                    onDismissRequest = { confirmExpanded = false },
                    title = { Text("Change category") },
                    text = { Text("Apply " + selectedCategory + " to future " + transaction.merchant + " transactions too?") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmExpanded = false
                            onChangeCategory(transaction, selectedCategory, true)
                        }) { Text("This & future") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            confirmExpanded = false
                            onChangeCategory(transaction, selectedCategory, false)
                        }) { Text("Only this") }
                    }
                )
            }

            if (addCategoryExpanded) {
                AlertDialog(
                    onDismissRequest = { addCategoryExpanded = false },
                    title = { Text("New category") },
                    text = {
                        OutlinedTextField(
                            value = newCategory,
                            onValueChange = { newCategory = it },
                            label = { Text("Category name") },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (newCategory.isNotBlank()) {
                                onAddCategory(newCategory.trim())
                                selectedCategory = newCategory.trim()
                                addCategoryExpanded = false
                                confirmExpanded = true
                                newCategory = ""
                            }
                        }) { Text("Add") }
                    },
                    dismissButton = {
                        TextButton(onClick = { addCategoryExpanded = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}

private fun List<Transaction>.filterBy(period: Period): List<Transaction> {
    if (period == Period.ALL) return this
    val now = Calendar.getInstance()
    val start = Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        when (period) {
            Period.TODAY -> Unit
            Period.WEEK -> set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            Period.MONTH -> set(Calendar.DAY_OF_MONTH, 1)
            Period.YEAR -> set(Calendar.DAY_OF_YEAR, 1)
            Period.ALL -> Unit
        }
    }
    return filter { it.timestamp >= start.timeInMillis }
}
