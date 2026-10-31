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
import androidx.compose.foundation.verticalScroll
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
    var displayedMonth by remember { mutableStateOf(Calendar.getInstance().get(Calendar.MONTH)) }
    var displayedYear by remember { mutableStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    var selectedDay by remember { mutableStateOf<Int?>(null) }
    val currency = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    val monthTransactions = remember(transactions, displayedMonth, displayedYear) { transactions.filterByMonth(displayedYear, displayedMonth) }
    val monthIncome = monthTransactions.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount }
    val monthExpenses = monthTransactions.filter { it.type == TransactionType.DEBIT && it.category != "Transfers" }.sumOf { it.amount }
    val selectedTransactions = selectedDay?.let { day ->
        monthTransactions.filter { transaction -> Calendar.getInstance().apply { timeInMillis = transaction.timestamp }.get(Calendar.DAY_OF_MONTH) == day }
    } ?: emptyList()
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        androidx.compose.foundation.verticalScroll(rememberScrollState()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("SpendSense", style = MaterialTheme.typography.headlineMedium); Text("Your money at a glance", style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = onScan) { Text("Scan SMS") }
        }
        Spacer(Modifier.height(14.dp))
        MonthHeader(displayedYear, displayedMonth, {
            if (displayedMonth == Calendar.JANUARY) { displayedMonth = Calendar.DECEMBER; displayedYear-- } else displayedMonth--; selectedDay = null
        }, {
            if (displayedMonth == Calendar.DECEMBER) { displayedMonth = Calendar.JANUARY; displayedYear++ } else displayedMonth++; selectedDay = null
        })
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Earned", currency.format(monthIncome), Modifier.weight(1f))
            MetricCard("Spent", currency.format(monthExpenses), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        MetricCard("Net cash flow", currency.format(monthIncome - monthExpenses), Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        CalendarView(displayedYear, displayedMonth, monthTransactions, selectedDay) { selectedDay = it }
        selectedDay?.let { day ->
            Spacer(Modifier.height(14.dp))
            Text("Transactions on " + dayLabel(displayedYear, displayedMonth, day), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            if (selectedTransactions.isEmpty()) Text("No transactions on this date.")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(selectedTransactions, key = { it.id }) { TransactionRow(it, currency, categories, onChangeCategory, onAddCategory) } }
        }
        }
    }
}

@Composable
private fun MonthHeader(year: Int, month: Int, onPrevious: () -> Unit, onNext: () -> Unit) {
    val label = java.text.SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Calendar.getInstance().apply { set(year, month, 1) }.time)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrevious) { Text("‹") }; Text(label, style = MaterialTheme.typography.titleLarge); TextButton(onClick = onNext) { Text("›") }
    }
}

@Composable
private fun CalendarView(year: Int, month: Int, transactions: List<Transaction>, selectedDay: Int?, onDayClick: (Int) -> Unit) {
    val calendar = Calendar.getInstance().apply { set(year, month, 1) }
    val firstDay = calendar.get(Calendar.DAY_OF_WEEK)
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    val leading = (firstDay - Calendar.MONDAY + 7) % 7
    val cells = ((leading + daysInMonth + 6) / 7) * 7
    val byDay = transactions.groupBy { Calendar.getInstance().apply { timeInMillis = it.timestamp }.get(Calendar.DAY_OF_MONTH) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth()) { listOf("M","T","W","T","F","S","S").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium) } }
            Spacer(Modifier.height(6.dp))
            for (row in 0 until cells / 7) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0..6) {
                        val day = row * 7 + col - leading + 1
                        if (day < 1 || day > daysInMonth) Spacer(Modifier.weight(1f).height(64.dp))
                        else {
                            val dayTransactions = byDay[day].orEmpty()
                            val income = dayTransactions.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount }
                            val spent = dayTransactions.filter { it.type == TransactionType.DEBIT && it.category != "Transfers" }.sumOf { it.amount }
                            val hasIncome = income > 0
                            val hasSpending = spent > 0
                            val cellColor = when {
                                selectedDay == day -> MaterialTheme.colorScheme.primaryContainer
                                hasIncome && hasSpending -> MaterialTheme.colorScheme.tertiaryContainer
                                hasIncome -> MaterialTheme.colorScheme.secondaryContainer
                                hasSpending -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                            Card(
                                onClick = { onDayClick(day) },
                                modifier = Modifier.weight(1f).padding(2.dp),
                                colors = CardDefaults.cardColors(containerColor = cellColor)
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().height(78.dp).padding(5.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(day.toString(), style = MaterialTheme.typography.labelLarge)
                                    if (hasIncome) {
                                        Text(
                                            "↑ ₹" + compactAmount(income),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (hasSpending) {
                                        Text(
                                            "↓ ₹" + compactAmount(spent),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    if (!hasIncome && !hasSpending) {
                                        Text("—", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp)); Text("+ earnings   - spending   • tap a date for details", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun compactAmount(amount: Double): String = if (amount >= 100000) String.format(Locale.getDefault(), "%.1fL", amount / 100000) else if (amount >= 1000) String.format(Locale.getDefault(), "%.1fk", amount / 1000) else String.format(Locale.getDefault(), "%.0f", amount)
private fun dayLabel(year: Int, month: Int, day: Int): String = java.text.SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Calendar.getInstance().apply { set(year, month, day) }.time)
private fun List<Transaction>.filterByMonth(year: Int, month: Int): List<Transaction> = filter { val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }; c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month }

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
