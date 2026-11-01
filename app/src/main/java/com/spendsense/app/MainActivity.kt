package com.spendsense.app

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import java.util.Calendar
import java.util.Date
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
import java.text.SimpleDateFormat
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
                    SpendSenseApp(
                        transactions = transactions,
                        categories = categories.map { it.name },
                        onScan = ::requestSmsPermission,
                        onChangeCategory = { transaction, category, future ->
                            lifecycleScope.launch {
                                if (future) repository.setCategoryForMerchant(transaction.merchant, category)
                                else repository.setCategory(transaction.id, category)
                            }
                        },
                        onChangeBank = { transaction, bank ->
                            lifecycleScope.launch { repository.setBank(transaction.id, bank) }
                        },
                        onDeleteTransaction = { transaction ->
                            lifecycleScope.launch { repository.deleteTransaction(transaction.id) }
                        },
                        onAddCategory = { name ->
                            lifecycleScope.launch { repository.addCustomCategory(name) }
                        },
                        onAddTransaction = { amount, type, merchant, category, bank, timestamp ->
                            lifecycleScope.launch {
                                repository.addManualTransaction(amount, type, merchant, category, bank, timestamp)
                            }
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
private fun SpendSenseApp(
    transactions: List<Transaction>,
    categories: List<String>,
    onScan: () -> Unit,
    onChangeCategory: (Transaction, String, Boolean) -> Unit,
    onChangeBank: (Transaction, String) -> Unit,
    onDeleteTransaction: (Transaction) -> Unit,
    onAddCategory: (String) -> Unit,
    onAddTransaction: (Double, TransactionType, String, String, String, Long) -> Unit
) {
    var page by remember { mutableStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    page == 0,
                    { page = 0 },
                    icon = { Text("⌂", fontSize = 26.sp) },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    page == 1,
                    { page = 1 },
                    icon = { Text("↕", fontSize = 26.sp) },
                    label = { Text("Activity") }
                )
                NavigationBarItem(
                    page == 2,
                    { page = 2 },
                    icon = { Text("▥", fontSize = 26.sp) },
                    label = { Text("Graphs") }
                )
            }
        }
    ) { padding ->
        when (page) {
            0 -> HomePage(transactions, categories, onScan, onChangeCategory, onChangeBank, onDeleteTransaction, onAddCategory, Modifier.padding(padding))
            1 -> ActivityPage(transactions, categories, onChangeCategory, onChangeBank, onDeleteTransaction, onAddCategory, onAddTransaction, Modifier.padding(padding))
            else -> GraphsPage(transactions, Modifier.padding(padding))
        }
    }
}

@Composable
private fun ActivityPage(
    transactions: List<Transaction>,
    categories: List<String>,
    onChangeCategory: (Transaction, String, Boolean) -> Unit,
    onChangeBank: (Transaction, String) -> Unit,
    onDeleteTransaction: (Transaction) -> Unit,
    onAddCategory: (String) -> Unit,
    onAddTransaction: (Double, TransactionType, String, String, String, Long) -> Unit,
    modifier: Modifier
) {
    var showAdd by remember { mutableStateOf(false) }
    val currency = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Activity", style = MaterialTheme.typography.headlineMedium)
            Text(transactions.size.toString() + " transactions", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                val monthGroups = transactions
                    .groupBy {
                        Calendar.getInstance().apply { timeInMillis = it.timestamp }
                            .let { cal -> cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH) }
                    }
                    .toSortedMap(compareByDescending<Pair<Int, Int>> { it.first }.thenByDescending { it.second })

                monthGroups.forEach { (yearMonth, monthTransactions) ->
                    val (year, month) = yearMonth
                    item(key = "month-$year-$month") {
                        Text(
                            SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(
                                Calendar.getInstance().apply { set(year, month, 1) }.time
                            ),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(monthTransactions, key = { it.id }) { transaction ->
                        TransactionRow(transaction, currency, categories, onChangeCategory, onChangeBank, onDeleteTransaction, onAddCategory)
                    }
                }
            }
        }
        FloatingActionButton(onClick = { showAdd = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)) { Text("+") }
    }
    if (showAdd) {
        AddTransactionDialog(categories, { showAdd = false }) { amount, type, merchant, category, bank, timestamp ->
            onAddTransaction(amount, type, merchant, category, bank, timestamp)
            showAdd = false
        }
    }
}

@Composable
private fun AddTransactionDialog(
    categories: List<String>,
    onDismiss: () -> Unit,
    onAdd: (Double, TransactionType, String, String, String, Long) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var bank by remember { mutableStateOf("Cash") }
    var type by remember { mutableStateOf(TransactionType.DEBIT) }
    var category by remember { mutableStateOf(categories.firstOrNull() ?: "Other") }
    var categoryMenu by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf(Calendar.getInstance()) }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(type == TransactionType.DEBIT, { type = TransactionType.DEBIT }, label = { Text("Spent") })
                    FilterChip(type == TransactionType.CREDIT, { type = TransactionType.CREDIT }, label = { Text("Earned") })
                }
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }, singleLine = true)
                OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant / description") }, singleLine = true)
                OutlinedTextField(bank, { bank = it }, label = { Text("Bank / Cash") }, singleLine = true)
                val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                OutlinedButton(
                    onClick = {
                        val current = selectedDate
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                selectedDate = Calendar.getInstance().apply { set(year, month, day, 12, 0, 0) }
                            },
                            current.get(Calendar.YEAR),
                            current.get(Calendar.MONTH),
                            current.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Date: " + dateFormat.format(selectedDate.time))
                }
                Box {
                    OutlinedButton(onClick = { categoryMenu = true }) { Text("Category: " + category) }
                    DropdownMenu(categoryMenu, { categoryMenu = false }) {
                        categories.forEach { item ->
                            DropdownMenuItem(text = { Text(item) }, onClick = { category = item; categoryMenu = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                amount.toDoubleOrNull()?.takeIf { it > 0 }?.let { onAdd(it, type, merchant, category, bank, selectedDate.timeInMillis) }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun GraphsPage(transactions: List<Transaction>, modifier: Modifier) {
    var range by remember { mutableStateOf(7) }
    val now = Calendar.getInstance()
    val start = Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis
        add(Calendar.DAY_OF_YEAR, -(range - 1))
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val spending = transactions.filter {
        it.type == TransactionType.DEBIT &&
            it.category != "Transfers" &&
            it.category != "Investments" &&
            it.timestamp >= start.timeInMillis
    }
    val days = (0 until range).map { offset ->
        Calendar.getInstance().apply {
            timeInMillis = start.timeInMillis
            add(Calendar.DAY_OF_YEAR, offset)
        }
    }
    val daily = days.map { day ->
        spending.filter {
            val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }
            c.get(Calendar.YEAR) == day.get(Calendar.YEAR) &&
                c.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
        }.sumOf { it.amount }
    }
    val byCategory = spending
        .groupBy { it.category }
        .mapValues { (_, values) -> values.sumOf { it.amount } }
        .toList()
        .sortedByDescending { it.second }
    val totalSpent = spending.sumOf { it.amount }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("Insights", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (range == 7) "Your spending over the last 7 days" else "Your spending over the last 30 days",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(range == 7, { range = 7 }, label = { Text("7 days") })
            FilterChip(range == 30, { range = 30 }, label = { Text("30 days") })
        }
        Spacer(Modifier.height(14.dp))
        SpendingBarChart(days, daily)
        Spacer(Modifier.height(12.dp))
        CategoryBreakdown(byCategory, totalSpent)
    }
}

@Composable
private fun SpendingBarChart(days: List<Calendar>, values: List<Double>) {
    val barColor = MaterialTheme.colorScheme.primary
    val maxValue = maxOf(1.0, values.maxOrNull() ?: 1.0)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Daily spending", style = MaterialTheme.typography.titleMedium)
                Text("Peak " + compactAmount(maxValue), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().height(190.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                days.forEachIndexed { i, day ->
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (values[i] > 0) {
                            Text(compactAmount(values[i]), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            Spacer(Modifier.height(3.dp))
                        } else {
                            Spacer(Modifier.height(14.dp))
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight((values[i] / maxValue).toFloat().coerceAtLeast(if (values[i] > 0) 0.025f else 0f))
                                .background(barColor, MaterialTheme.shapes.small)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            SimpleDateFormat("EEE", Locale.getDefault()).format(day.time).take(1),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBreakdown(categories: List<Pair<String, Double>>, total: Double) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("By category", style = MaterialTheme.typography.titleMedium)
                Text(compactAmount(total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            if (categories.isEmpty()) {
                Text("No spending data for this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                categories.take(6).forEach { (name, amount) ->
                    val fraction = if (total > 0) (amount / total).toFloat() else 0f
                    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(compactAmount(amount), style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(5.dp))
                        Box(
                            Modifier.fillMaxWidth().height(7.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                        ) {
                            Box(
                                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomePage(
    transactions: List<Transaction>,
    categories: List<String>,
    onScan: () -> Unit,
    onChangeCategory: (Transaction, String, Boolean) -> Unit,
    onChangeBank: (Transaction, String) -> Unit,
    onDeleteTransaction: (Transaction) -> Unit,
    onAddCategory: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var displayedMonth by remember { mutableStateOf(Calendar.getInstance().get(Calendar.MONTH)) }
    var displayedYear by remember { mutableStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    var selectedDay by remember { mutableStateOf<Int?>(null) }
    val currency = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    val monthTransactions = remember(transactions, displayedMonth, displayedYear) { transactions.filterByMonth(displayedYear, displayedMonth) }
    val monthIncome = monthTransactions.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount }
    val monthExpenses = monthTransactions.filter { it.type == TransactionType.DEBIT && it.category != "Transfers" }.sumOf { it.amount }
    val monthInvested = monthTransactions.filter { it.type == TransactionType.DEBIT && it.category == "Investments" }.sumOf { it.amount }
    val selectedTransactions = selectedDay?.let { day ->
        monthTransactions.filter { transaction -> Calendar.getInstance().apply { timeInMillis = transaction.timestamp }.get(Calendar.DAY_OF_MONTH) == day }
    } ?: emptyList()
    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState())
    ) {
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Earned", currency.format(monthIncome), Modifier.weight(1f))
            MetricCard("Spent", currency.format(monthExpenses - monthInvested), Modifier.weight(1f))
            MetricCard("Invested", currency.format(monthInvested), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Net cash flow", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(currency.format(monthIncome - monthExpenses), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        CalendarView(displayedYear, displayedMonth, monthTransactions, selectedDay) { selectedDay = it }
        selectedDay?.let { day ->
            Spacer(Modifier.height(14.dp))
            Text("Transactions on " + dayLabel(displayedYear, displayedMonth, day), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            if (selectedTransactions.isEmpty()) Text("No transactions on this date.")
            else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                selectedTransactions.forEach { transaction ->
                    TransactionRow(transaction, currency, categories, onChangeCategory, onChangeBank, onDeleteTransaction, onAddCategory)
                }
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
                        if (day < 1 || day > daysInMonth) Spacer(Modifier.weight(1f).height(44.dp))
                        else {
                            val dayTransactions = byDay[day].orEmpty()
                            val income = dayTransactions.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount }
                            val spent = dayTransactions.filter { it.type == TransactionType.DEBIT && it.category != "Transfers" }.sumOf { it.amount }
                            val hasIncome = income > 0
                            val hasSpending = spent > 0
                            val cellColor = when {
                                selectedDay == day -> Color(0xFF3949AB)
                                hasIncome && hasSpending && income > spent -> Color(0xFF355E3B)
                                hasIncome && hasSpending -> Color(0xFF6B3F45)
                                hasIncome -> Color(0xFF1F6B45)
                                hasSpending -> Color(0xFF8F2020)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                            Card(
                                onClick = { onDayClick(day) },
                                modifier = Modifier.weight(1f).padding(2.dp),
                                colors = CardDefaults.cardColors(containerColor = cellColor)
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().height(40.dp).padding(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    Text(day.toString(), style = MaterialTheme.typography.labelLarge)
                                    if (hasIncome) {
                                        Text(
                                            "↑" + compactAmount(income),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF9BE7B5),
                                            maxLines = 1
                                        )
                                    }
                                    if (hasSpending) {
                                        Text(
                                            "↓" + compactAmount(spent),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFFFFB0B0),
                                            maxLines = 1
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
            Spacer(Modifier.height(6.dp))
            Text("Green = cash positive  •  Red = spending  •  tap a date for details", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun compactAmount(amount: Double): String = if (amount >= 100000) String.format(Locale.getDefault(), "%.1fL", amount / 100000) else if (amount >= 1000) String.format(Locale.getDefault(), "%.1fk", amount / 1000) else String.format(Locale.getDefault(), "%.0f", amount)
private fun dayLabel(year: Int, month: Int, day: Int): String = java.text.SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Calendar.getInstance().apply { set(year, month, day) }.time)
private fun List<Transaction>.filterByMonth(year: Int, month: Int): List<Transaction> = filter { val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }; c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month }

@Composable
private fun SpendSenseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8AB4F8),
            background = Color(0xFF0B0F14),
            surface = Color(0xFF151A21),
            surfaceVariant = Color(0xFF20262E)
        ),
        content = content
    )
}

@Composable
private fun BankBadge(
    sender: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val name = bankDisplayName(sender)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(if (name == "Cash") "₹" else bankInitials(name))
        }
    }
}

private fun bankDisplayName(sender: String): String {
    val s = sender.uppercase(Locale.getDefault())
    return when {
        s.contains("HDFC") -> "HDFC"
        s.contains("ICICI") -> "ICICI"
        s.contains("SBI") || s.contains("SBIN") -> "SBI"
        s.contains("AXIS") -> "Axis"
        s.contains("IDFC") -> "IDFC FIRST"
        s.contains("UCO") -> "UCO"
        s.contains("KOTAK") -> "Kotak"
        s.contains("YESBANK") || s.contains("YES BANK") -> "Yes Bank"
        s.contains("INDUSIND") -> "IndusInd"
        s.contains("PNB") -> "PNB"
        s.contains("BOB") || s.contains("BANKOFBARODA") -> "Bank of Baroda"
        s.contains("CANARA") -> "Canara"
        s.contains("FEDERAL") -> "Federal"
        s.contains("PAYTM") -> "Paytm"
        s.contains("PHONEPE") -> "PhonePe"
        s.contains("GOOGLEPAY") || s.contains("GPAY") -> "Google Pay"
        s.contains("AMAZONPAY") -> "Amazon Pay"
        s.contains("CASH") || s == "UNKNOWN" -> "Cash"
        else -> "Cash"
    }
}

private fun bankInitials(name: String): String = when {
    name == "IDFC FIRST" -> "ID"
    name == "Bank of Baroda" -> "BOB"
    name == "IndusInd" -> "II"
    name == "Yes Bank" -> "YES"
    name.length <= 3 -> name
    else -> name.take(3)
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun TransactionRow(
    transaction: Transaction,
    currency: NumberFormat,
    categories: List<String>,
    onChangeCategory: (Transaction, String, Boolean) -> Unit,
    onChangeBank: (Transaction, String) -> Unit,
    onDeleteTransaction: (Transaction) -> Unit,
    onAddCategory: (String) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmExpanded by remember { mutableStateOf(false) }
    var addCategoryExpanded by remember { mutableStateOf(false) }
    var bankDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var selectedCategory by remember(transaction.category) { mutableStateOf(transaction.category) }
    var newCategory by remember { mutableStateOf("") }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BankBadge(transaction.sender, Modifier.size(42.dp)) { bankDialog = true }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(transaction.merchant, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    transaction.category + " • " + bankDisplayName(transaction.sender),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    SimpleDateFormat("dd MMM · h:mm a", Locale.getDefault()).format(Date(transaction.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (transaction.type == TransactionType.CREDIT) "+ " else "− ") + currency.format(transaction.amount),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (transaction.type == TransactionType.CREDIT) Color(0xFF8FE3AE) else MaterialTheme.colorScheme.onSurface
                )
                TextButton(
                    onClick = { menuExpanded = true },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) { Text("Edit", fontSize = 12.sp) }
            }
        }
    }

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
        DropdownMenuItem(text = { Text("Change account") }, onClick = { menuExpanded = false; bankDialog = true })
        DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menuExpanded = false; deleteDialog = true })
        DropdownMenuItem(text = { Text("+ Add new category") }, onClick = { menuExpanded = false; addCategoryExpanded = true })
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("Delete transaction?") },
            text = { Text("This transaction will be permanently removed from SpendSense.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteTransaction(transaction)
                    deleteDialog = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } }
        )
    }

    if (bankDialog) {
        var bankName by remember(transaction.sender) { mutableStateOf(bankDisplayName(transaction.sender)) }
        AlertDialog(
            onDismissRequest = { bankDialog = false },
            title = { Text("Edit account") },
            text = {
                OutlinedTextField(value = bankName, onValueChange = { bankName = it }, label = { Text("Bank / Cash") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    onChangeBank(transaction, bankName.trim().ifBlank { "Cash" })
                    bankDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { bankDialog = false }) { Text("Cancel") } }
        )
    }

    if (confirmExpanded) {
        AlertDialog(
            onDismissRequest = { confirmExpanded = false },
            title = { Text("Change category") },
            text = { Text("Apply $selectedCategory to future " + transaction.merchant + " transactions too?") },
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
                OutlinedTextField(value = newCategory, onValueChange = { newCategory = it }, label = { Text("Category name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val cleaned = newCategory.trim()
                    if (cleaned.isNotBlank()) {
                        onAddCategory(cleaned)
                        selectedCategory = cleaned
                        newCategory = ""
                        addCategoryExpanded = false
                        confirmExpanded = true
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addCategoryExpanded = false }) { Text("Cancel") } }
        )
    }
}
