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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import kotlinx.coroutines.delay
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
                        onEditTransaction = { transaction, amount, type, merchant, timestamp ->
                            lifecycleScope.launch {
                                repository.updateTransaction(transaction.id, amount, type, merchant, timestamp)
                            }
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
    onEditTransaction: (Transaction, Double, TransactionType, String, Long) -> Unit,
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
                    icon = {
                        Text("⇄", fontSize = 24.sp)
                    },
                    label = { Text("Activity") }
                )
                NavigationBarItem(
                    page == 2,
                    { page = 2 },
                    icon = { Text("◎", fontSize = 26.sp) },
                    label = { Text("Targets") }
                )
                NavigationBarItem(
                    page == 3,
                    { page = 3 },
                    icon = { Text("▥", fontSize = 26.sp) },
                    label = { Text("Insights") }
                )
            }
        }
    ) { padding ->
        when (page) {
            0 -> HomePage(transactions, categories, onScan, onChangeCategory, onChangeBank, onDeleteTransaction, onEditTransaction, onAddCategory, Modifier.padding(padding))
            1 -> ActivityPage(transactions, categories, onChangeCategory, onChangeBank, onDeleteTransaction, onEditTransaction, onAddCategory, onAddTransaction, Modifier.padding(padding))
            2 -> TargetsPage(transactions, categories, Modifier.padding(padding))
            else -> InsightsPage(transactions, Modifier.padding(padding))
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
    onEditTransaction: (Transaction, Double, TransactionType, String, Long) -> Unit,
    onAddCategory: (String) -> Unit,
    onAddTransaction: (Double, TransactionType, String, String, String, Long) -> Unit,
    modifier: Modifier
) {
    var showAdd by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<TransactionType?>(null) }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var bankFilter by remember { mutableStateOf<String?>(null) }
    var monthFilter by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var categoryMenu by remember { mutableStateOf(false) }
    var bankMenu by remember { mutableStateOf(false) }
    var monthMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf("Newest") }

    val bankOptions = remember(transactions) { transactions.map { bankDisplayName(it.sender) }.distinct().sorted() }
    val monthOptions = remember(transactions) {
        transactions.map { Calendar.getInstance().apply { timeInMillis = it.timestamp }.let { c -> c.get(Calendar.YEAR) to c.get(Calendar.MONTH) } }
            .distinct().sortedWith(compareByDescending<Pair<Int, Int>> { it.first }.thenByDescending { it.second })
    }
    val filteredTransactions = remember(transactions, query, typeFilter, categoryFilter, bankFilter, monthFilter, sortMode) {
        val q = query.trim()
        transactions.filter { transaction ->
            val c = Calendar.getInstance().apply { timeInMillis = transaction.timestamp }
            val matchesQuery = q.isBlank() || transaction.merchant.contains(q, true) || transaction.category.contains(q, true) ||
                transaction.sender.contains(q, true) || bankDisplayName(transaction.sender).contains(q, true)
            val matchesType = typeFilter == null || transaction.type == typeFilter
            val matchesCategory = categoryFilter == null || transaction.category == categoryFilter
            val matchesBank = bankFilter == null || bankDisplayName(transaction.sender) == bankFilter
            val matchesMonth = monthFilter == null || (c.get(Calendar.YEAR) == monthFilter!!.first && c.get(Calendar.MONTH) == monthFilter!!.second)
            matchesQuery && matchesType && matchesCategory && matchesBank && matchesMonth
        }.let { list ->
            when (sortMode) {
                "Oldest" -> list.sortedBy { it.timestamp }
                "Highest amount" -> list.sortedByDescending { it.amount }
                "Lowest amount" -> list.sortedBy { it.amount }
                else -> list.sortedByDescending { it.timestamp }
            }
        }
    }
    val currency = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Activity", style = MaterialTheme.typography.headlineMedium)
                    Text(filteredTransactions.size.toString() + " of " + transactions.size + " transactions", style = MaterialTheme.typography.bodyMedium)
                }
                Box {
                    TextButton(onClick = { sortMenu = true }) { Text(sortMode) }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf("Newest", "Oldest", "Highest amount", "Lowest amount").forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { sortMode = option; sortMenu = false })
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Search merchant, category or bank") }, singleLine = true,
                trailingIcon = { if (query.isNotBlank()) TextButton(onClick = { query = "" }) { Text("Clear") } }
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(typeFilter == null, { typeFilter = null }, label = { Text("All") })
                FilterChip(typeFilter == TransactionType.DEBIT, { typeFilter = if (typeFilter == TransactionType.DEBIT) null else TransactionType.DEBIT }, label = { Text("Spent") })
                FilterChip(typeFilter == TransactionType.CREDIT, { typeFilter = if (typeFilter == TransactionType.CREDIT) null else TransactionType.CREDIT }, label = { Text("Earned") })
                Box {
                    FilterChip(categoryFilter != null, { categoryMenu = true }, label = { Text(categoryFilter ?: "Category") })
                    DropdownMenu(categoryMenu, { categoryMenu = false }) {
                        DropdownMenuItem(text = { Text("All categories") }, onClick = { categoryFilter = null; categoryMenu = false })
                        categories.forEach { category -> DropdownMenuItem(text = { Text(category) }, onClick = { categoryFilter = category; categoryMenu = false }) }
                    }
                }
                Box {
                    FilterChip(bankFilter != null, { bankMenu = true }, label = { Text(bankFilter ?: "Bank") })
                    DropdownMenu(bankMenu, { bankMenu = false }) {
                        DropdownMenuItem(text = { Text("All banks") }, onClick = { bankFilter = null; bankMenu = false })
                        bankOptions.forEach { bank -> DropdownMenuItem(text = { Text(bank) }, onClick = { bankFilter = bank; bankMenu = false }) }
                    }
                }
                Box {
                    val monthLabel = monthFilter?.let { SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Calendar.getInstance().apply { set(it.first, it.second, 1) }.time) } ?: "Month"
                    FilterChip(monthFilter != null, { monthMenu = true }, label = { Text(monthLabel) })
                    DropdownMenu(monthMenu, { monthMenu = false }) {
                        DropdownMenuItem(text = { Text("All months") }, onClick = { monthFilter = null; monthMenu = false })
                        monthOptions.forEach { (year, month) ->
                            DropdownMenuItem(
                                text = { Text(SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Calendar.getInstance().apply { set(year, month, 1) }.time)) },
                                onClick = { monthFilter = year to month; monthMenu = false }
                            )
                        }
                    }
                }
            }
            val hasFilters = query.isNotBlank() || typeFilter != null || categoryFilter != null || bankFilter != null || monthFilter != null || sortMode != "Newest"
            if (hasFilters) {
                TextButton(
                    onClick = {
                        query = ""
                        typeFilter = null
                        categoryFilter = null
                        bankFilter = null
                        monthFilter = null
                        sortMode = "Newest"
                    },
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Clear filters") }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                val monthGroups = filteredTransactions.groupBy {
                    Calendar.getInstance().apply { timeInMillis = it.timestamp }.let { cal -> cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH) }
                }.toSortedMap(compareByDescending<Pair<Int, Int>> { it.first }.thenByDescending { it.second })
                if (filteredTransactions.isEmpty()) {
                    item { Text("No transactions match your filters.", Modifier.fillMaxWidth().padding(vertical = 28.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                monthGroups.forEach { (yearMonth, monthTransactions) ->
                    val (year, month) = yearMonth
                    item(key = "month-" + year + "-" + month) {
                        Text(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Calendar.getInstance().apply { set(year, month, 1) }.time), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    }
                    items(monthTransactions, key = { it.id }) { transaction ->
                        TransactionRow(transaction, currency, categories, onChangeCategory, onChangeBank, onDeleteTransaction, onEditTransaction, onAddCategory)
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

private fun targetValue(prefs: android.content.SharedPreferences, key: String): Double {
    return prefs.getFloat(key, 0f).toDouble()
}

private fun monthlyOrAllTarget(
    prefs: android.content.SharedPreferences,
    prefix: String,
    year: Int,
    month: Int,
    category: String? = null
): Double {
    val monthKey = if (category == null) {
        prefix + "_" + year + "_" + month
    } else {
        prefix + "_" + year + "_" + month + "_" + category
    }
    val allKey = if (category == null) {
        prefix + "_all"
    } else {
        prefix + "_all_" + category
    }
    val monthValue = prefs.getFloat(monthKey, 0f).toDouble()
    return if (monthValue > 0) monthValue else prefs.getFloat(allKey, 0f).toDouble()
}

private fun targetCategories(prefs: android.content.SharedPreferences): List<String> =
    prefs.getStringSet("target_categories", emptySet()).orEmpty().toList().sorted()

private fun categoryEmoji(category: String): String = when (category.lowercase(Locale.ROOT)) {
    "food" -> "🍔"
    "groceries" -> "🛒"
    "shopping" -> "🛍️"
    "fuel" -> "⛽"
    "transport" -> "🚗"
    "bills" -> "💡"
    "insurance" -> "🛡️"
    "health" -> "💊"
    "travel" -> "✈️"
    "entertainment" -> "🎬"
    "subscriptions" -> "📺"
    "family" -> "👨‍👩‍👧"
    "rent" -> "🏠"
    "education" -> "📚"
    else -> "🎯"
}

private fun categoryAccent(category: String): Color = when (category.lowercase(Locale.ROOT)) {
    "food" -> Color(0xFFFF8A65)
    "groceries" -> Color(0xFF26A69A)
    "shopping" -> Color(0xFFBA68C8)
    "fuel" -> Color(0xFFFFB74D)
    "transport" -> Color(0xFF64B5F6)
    "bills" -> Color(0xFFFFD54F)
    "insurance" -> Color(0xFF7986CB)
    "health" -> Color(0xFFEF5350)
    "travel" -> Color(0xFF4DD0E1)
    "entertainment" -> Color(0xFFE57373)
    "subscriptions" -> Color(0xFFAB47BC)
    "family" -> Color(0xFFEC407A)
    "rent" -> Color(0xFF81C784)
    "education" -> Color(0xFF42A5F5)
    else -> Color(0xFF8AB4F8)
}

@Composable
private fun TargetsPage(
    transactions: List<Transaction>,
    categories: List<String>,
    modifier: Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("spendsense_budget", android.content.Context.MODE_PRIVATE) }
    var displayedMonth by remember { mutableStateOf(Calendar.getInstance().get(Calendar.MONTH)) }
    var displayedYear by remember { mutableStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    var showSpendDialog by remember { mutableStateOf(false) }
    var showInvestDialog by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(categories.firstOrNull() ?: "Food") }

    val monthTransactions = remember(transactions, displayedMonth, displayedYear) {
        transactions.filterByMonth(displayedYear, displayedMonth)
    }
    val spent = monthTransactions
        .filter { it.type == TransactionType.DEBIT && it.category != "Transfers" && it.category != "Investments" }
        .sumOf { it.amount }
    val invested = monthTransactions
        .filter { it.type == TransactionType.DEBIT && it.category == "Investments" }
        .sumOf { it.amount }

    val spendTarget = monthlyOrAllTarget(prefs, "spend_target", displayedYear, displayedMonth)
    val investTarget = monthlyOrAllTarget(prefs, "invest_target", displayedYear, displayedMonth)
    val configuredCategories = targetCategories(prefs)
    val spendProgress = if (spendTarget > 0) (spent / spendTarget).coerceIn(0.0, 1.0) else 0.0
    val investProgress = if (investTarget > 0) (invested / investTarget).coerceIn(0.0, 1.0) else 0.0

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("Targets", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Set monthly goals for spending, investing and categories",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        MonthHeader(
            displayedYear,
            displayedMonth,
            {
                if (displayedMonth == Calendar.JANUARY) { displayedMonth = Calendar.DECEMBER; displayedYear-- } else displayedMonth--
            },
            {
                if (displayedMonth == Calendar.DECEMBER) { displayedMonth = Calendar.JANUARY; displayedYear++ } else displayedMonth++
            }
        )

        Spacer(Modifier.height(10.dp))
        TargetCard(
            title = "Spend target",
            actual = spent,
            target = spendTarget,
            progress = spendProgress,
            goodWhenUnder = true,
            onEdit = { showSpendDialog = true }
        )
        Spacer(Modifier.height(10.dp))
        TargetCard(
            title = "Invest target",
            actual = invested,
            target = investTarget,
            progress = investProgress,
            goodWhenUnder = false,
            onEdit = { showInvestDialog = true }
        )

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Category targets", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Only categories you choose appear here",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = {
                selectedCategory = categories.filter { it != "Investments" && it != "Transfers" }.sorted().firstOrNull() ?: "Food"
                showCategoryDialog = true
            }) { Text("＋ Add") }
        }

        Spacer(Modifier.height(8.dp))
        if (configuredCategories.isEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2030))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("🎯 Build your own targets", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap ＋ Add and choose only the categories you want to track.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            configuredCategories.forEach { category ->
                val target = monthlyOrAllTarget(prefs, "category_target", displayedYear, displayedMonth, category)
                val actual = monthTransactions
                    .filter { it.type == TransactionType.DEBIT && it.category == category }
                    .sumOf { it.amount }
                CategoryTargetRow(
                    category = category,
                    actual = actual,
                    target = target,
                    onEdit = {
                        selectedCategory = category
                        showCategoryDialog = true
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "💡 Set a target for this month or apply it to every month.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showSpendDialog) {
        BudgetDialog(
            current = spendTarget,
            title = "Monthly spend target",
            onDismiss = { showSpendDialog = false },
            onSave = { amount, allMonths ->
                val edit = prefs.edit()
                if (allMonths) edit.putFloat("spend_target_all", amount.toFloat())
                else edit.putFloat("spend_target_" + displayedYear + "_" + displayedMonth, amount.toFloat())
                edit.apply()
                showSpendDialog = false
            }
        )
    }

    if (showInvestDialog) {
        BudgetDialog(
            current = investTarget,
            title = "Monthly invest target",
            onDismiss = { showInvestDialog = false },
            onSave = { amount, allMonths ->
                val edit = prefs.edit()
                if (allMonths) edit.putFloat("invest_target_all", amount.toFloat())
                else edit.putFloat("invest_target_" + displayedYear + "_" + displayedMonth, amount.toFloat())
                edit.apply()
                showInvestDialog = false
            }
        )
    }

    if (showCategoryDialog) {
        CategoryTargetDialog(
            categories = categories.filter { it != "Investments" && it != "Transfers" }.sorted(),
            selectedCategory = selectedCategory,
            prefs = prefs,
            year = displayedYear,
            month = displayedMonth,
            spending = monthTransactions.filter { it.type == TransactionType.DEBIT && it.category != "Transfers" && it.category != "Investments" },
            onDismiss = { showCategoryDialog = false }
        )
    }
}

@Composable
private fun TargetCard(
    title: String,
    actual: Double,
    target: Double,
    progress: Double,
    goodWhenUnder: Boolean,
    onEdit: () -> Unit
) {
    val accent = if (goodWhenUnder) Color(0xFFFF7043) else Color(0xFF00C853)
    val emoji = if (goodWhenUnder) "💸" else "🚀"
    val subtitle = if (goodWhenUnder) "Keep your spending on track" else "Grow your investments"
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.14f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .background(accent.copy(alpha = 0.22f), MaterialTheme.shapes.large),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emoji, fontSize = 24.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onEdit) {
                    Text(if (target > 0) "Edit" else "Set", color = accent)
                }
            }

            Spacer(Modifier.height(12.dp))

            if (target > 0) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        currencyFormat(actual),
                        style = MaterialTheme.typography.titleLarge,
                        color = accent
                    )
                    Text(
                        "of " + currencyFormat(target),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(7.dp))
                LinearProgressIndicator(
                    progress = { progress.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.18f)
                )
                Spacer(Modifier.height(6.dp))
                val message = if (goodWhenUnder) {
                    if (actual > target) "⚠️ Over target by " + currencyFormat(actual - target)
                    else "✨ " + currencyFormat(target - actual) + " remaining"
                } else {
                    if (actual >= target) "🎉 Target reached!"
                    else "🔥 " + currencyFormat(target - actual) + " left to invest"
                }
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (goodWhenUnder && actual > target)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "🎯 No target set yet — let’s make one!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CategoryTargetRow(
    category: String,
    actual: Double,
    target: Double,
    onEdit: () -> Unit
) {
    val accent = categoryAccent(category)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f))
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(categoryEmoji(category), fontSize = 22.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(category, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (target > 0) currencyFormat(actual) + " / " + currencyFormat(target)
                        else currencyFormat(actual) + " spent • no target",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onEdit) { Text(if (target > 0) "Edit" else "Set", color = accent) }
            }
            if (target > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (actual / target).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = accent
                )
            }
        }
    }
}

@Composable
private fun CategoryTargetDialog(
    categories: List<String>,
    selectedCategory: String,
    prefs: android.content.SharedPreferences,
    year: Int,
    month: Int,
    spending: List<Transaction>,
    onDismiss: () -> Unit
) {
    var selected by remember(selectedCategory) { mutableStateOf(selectedCategory) }
    var amount by remember { mutableStateOf("") }
    var allMonths by remember { mutableStateOf(false) }
    val monthKey = "category_target_" + year + "_" + month + "_" + selected
    val allKey = "category_target_all_" + selected
    LaunchedEffect(selected, year, month) {
        val current = monthlyOrAllTarget(prefs, "category_target", year, month, selected)
        amount = if (current > 0) current.toString() else ""
        allMonths = prefs.getFloat(allKey, 0f) > 0 && prefs.getFloat(monthKey, 0f) == 0f
    }

    val spent = spending.filter { it.category == selected }.sumOf { it.amount }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎯 Category target") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose a category to track", style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { category ->
                        FilterChip(
                            selected == category,
                            { selected = category },
                            label = { Text(categoryEmoji(category) + " " + category) }
                        )
                    }
                }
                Text(
                    "Spent this month: " + currencyFormat(spent),
                    style = MaterialTheme.typography.labelMedium,
                    color = categoryAccent(selected)
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Target amount") },
                    prefix = { Text("₹") },
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allMonths, onCheckedChange = { allMonths = it })
                    Text("Use this target for all months")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                amount.toDoubleOrNull()?.takeIf { it > 0 }?.let {
                    prefs.edit()
                        .putFloat(if (allMonths) allKey else monthKey, it.toFloat())
                        .putStringSet(
                            "target_categories",
                            prefs.getStringSet("target_categories", emptySet()).orEmpty() + selected
                        )
                        .apply()
                    onDismiss()
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


@Composable
private fun InsightsPage(transactions: List<Transaction>, modifier: Modifier) {
    var period by remember { mutableStateOf("MONTH") }
    var anchor by remember {
        mutableStateOf(Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        })
    }
    val context = LocalContext.current
    val recurringPrefs = remember {
        context.getSharedPreferences("spendsense_recurring", android.content.Context.MODE_PRIVATE)
    }
    var editingRecurring by remember { mutableStateOf<RecurringCandidate?>(null) }
    var viewingRecurring by remember { mutableStateOf<RecurringCandidate?>(null) }
    var autoScanRecurring by remember {
        mutableStateOf(recurringPrefs.getBoolean("auto_scan", true))
    }
    var recurringCandidates by remember(transactions, autoScanRecurring) {
        mutableStateOf(
            findRecurringCandidates(transactions).mapNotNull { candidate ->
                val key = recurringKey(candidate.merchant)
                if (recurringPrefs.getBoolean("deleted_$key", false)) {
                    null
                } else {
                    candidate.copy(
                        merchant = recurringPrefs.getString("merchant_$key", candidate.merchant) ?: candidate.merchant,
                        averageAmount = recurringPrefs.getString("amount_$key", null)?.toDoubleOrNull() ?: candidate.averageAmount,
                        category = recurringPrefs.getString("category_$key", candidate.category) ?: candidate.category,
                        dayOfMonth = recurringPrefs.getInt("day_$key", candidate.dayOfMonth)
                    )
                }
            }
        )
    }

    val start = Calendar.getInstance().apply {
        timeInMillis = anchor.timeInMillis
        when (period) {
            "WEEK" -> {
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            "MONTH" -> {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            else -> {
                set(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
        }
    }
    val end = Calendar.getInstance().apply {
        timeInMillis = start.timeInMillis
        when (period) {
            "WEEK" -> add(Calendar.DAY_OF_YEAR, 7)
            "MONTH" -> add(Calendar.MONTH, 1)
            else -> add(Calendar.YEAR, 1)
        }
    }
    val periodTransactions = transactions.filter { it.timestamp >= start.timeInMillis && it.timestamp < end.timeInMillis }
    val spending = periodTransactions.filter {
        it.type == TransactionType.DEBIT && it.category != "Transfers" && it.category != "Investments"
    }
    val totalSpent = spending.sumOf { it.amount }
    val income = periodTransactions.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount }
    val invested = periodTransactions.filter {
        it.type == TransactionType.DEBIT && it.category == "Investments"
    }.sumOf { it.amount }

    val rawByCategory = spending.groupBy { it.category }
        .mapValues { (_, values) -> values.sumOf { it.amount } }
        .toList().sortedByDescending { it.second }
    val topCategories = rawByCategory.take(5)
    val otherTotal = rawByCategory.drop(5).sumOf { it.second }
    val byCategory = topCategories + if (otherTotal > 0) listOf("Other" to otherTotal) else emptyList()

    fun move(direction: Int) {
        anchor = Calendar.getInstance().apply {
            timeInMillis = anchor.timeInMillis
            when (period) {
                "WEEK" -> add(Calendar.WEEK_OF_YEAR, direction)
                "MONTH" -> add(Calendar.MONTH, direction)
                else -> add(Calendar.YEAR, direction)
            }
        }
    }

    val periodLabel = when (period) {
        "WEEK" -> {
            val weekEnd = Calendar.getInstance().apply { timeInMillis = start.timeInMillis; add(Calendar.DAY_OF_YEAR, 6) }
            SimpleDateFormat("dd MMM", Locale.getDefault()).format(start.time) + " – " +
                SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(weekEnd.time)
        }
        "MONTH" -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(start.time)
        else -> start.get(Calendar.YEAR).toString()
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("Insights", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Explore your spending by week, month and year",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(period == "WEEK", { period = "WEEK"; anchor = Calendar.getInstance() }, label = { Text("Week") })
            FilterChip(period == "MONTH", { period = "MONTH"; anchor = Calendar.getInstance() }, label = { Text("Month") })
            FilterChip(period == "YEAR", { period = "YEAR"; anchor = Calendar.getInstance() }, label = { Text("Year") })
        }

        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { move(-1) }) { Text("‹", fontSize = 30.sp) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when (period) {
                            "WEEK" -> "Selected week"
                            "MONTH" -> "Selected month"
                            else -> "Selected year"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(periodLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { move(1) }) { Text("›", fontSize = 30.sp) }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Earned", currencyFormat(income), Modifier.weight(1f), Color(0xFF43A047), "↑")
            MetricCard("Spent", currencyFormat(totalSpent), Modifier.weight(1f), Color(0xFFFF7043), "↓")
            MetricCard("Invested", currencyFormat(invested), Modifier.weight(1f), Color(0xFF8E44AD), "↗")
        }

        Spacer(Modifier.height(14.dp))
        if (period == "YEAR") {
            YearlyMonthlyBarChart(start.get(Calendar.YEAR), transactions)
        } else {
            val days = (0 until if (period == "WEEK") 7 else start.getActualMaximum(Calendar.DAY_OF_MONTH)).map { offset ->
                Calendar.getInstance().apply {
                    timeInMillis = start.timeInMillis
                    add(Calendar.DAY_OF_YEAR, offset)
                }
            }
            val daily = days.map { day ->
                spending.filter {
                    val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }
                    c.get(Calendar.YEAR) == day.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
                }.sumOf { it.amount }
            }
            SpendingBarChart(days, daily, spending)
        }

        Spacer(Modifier.height(12.dp))
        CategoryPieChart(byCategory, totalSpent, spending)

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Recurring Payments", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (autoScanRecurring) "Auto-scans your transactions for repeated monthly payments"
                    else "Scan manually when you want to check for recurring payments",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = autoScanRecurring,
                onCheckedChange = { enabled ->
                    autoScanRecurring = enabled
                    recurringPrefs.edit().putBoolean("auto_scan", enabled).apply()
                    if (enabled) {
                        recurringCandidates = findRecurringCandidates(transactions).mapNotNull { candidate ->
                            val key = recurringKey(candidate.merchant)
                            if (recurringPrefs.getBoolean("deleted_$key", false)) null
                            else candidate.copy(
                                merchant = recurringPrefs.getString("merchant_$key", candidate.merchant) ?: candidate.merchant,
                                averageAmount = recurringPrefs.getString("amount_$key", null)?.toDoubleOrNull() ?: candidate.averageAmount,
                                category = recurringPrefs.getString("category_$key", candidate.category) ?: candidate.category,
                                dayOfMonth = recurringPrefs.getInt("day_$key", candidate.dayOfMonth)
                            )
                        }
                    }
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = {
                recurringCandidates = findRecurringCandidates(transactions).mapNotNull { candidate ->
                    val key = recurringKey(candidate.merchant)
                    if (recurringPrefs.getBoolean("deleted_$key", false)) null
                    else candidate.copy(
                        merchant = recurringPrefs.getString("merchant_$key", candidate.merchant) ?: candidate.merchant,
                        averageAmount = recurringPrefs.getString("amount_$key", null)?.toDoubleOrNull() ?: candidate.averageAmount,
                        category = recurringPrefs.getString("category_$key", candidate.category) ?: candidate.category,
                        dayOfMonth = recurringPrefs.getInt("day_$key", candidate.dayOfMonth)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🔄 Scan for recurring transactions")
        }
        Spacer(Modifier.height(8.dp))
        if (recurringCandidates.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text("No recurring payments detected yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            recurringCandidates.forEach { candidate ->
                val accent = recurringAccent(candidate.category)
                Card(
                    onClick = { viewingRecurring = candidate },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.14f))
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(42.dp).background(accent.copy(alpha = 0.22f), MaterialTheme.shapes.medium),
                            contentAlignment = Alignment.Center
                        ) { Text("↻", fontSize = 23.sp, color = accent) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(candidate.merchant, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(candidate.category + " • " + candidate.count + " payments", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(currencyFormat(candidate.averageAmount) + " / month", style = MaterialTheme.typography.titleSmall, color = accent)
                        }
                        TextButton(onClick = { editingRecurring = candidate }) { Text("Edit", color = accent) }
                    }
                }
            }
        }
    }

    viewingRecurring?.let { candidate ->
        val entries = transactions
            .filter { it.merchant.trim().equals(candidate.originalMerchant.trim(), ignoreCase = true) }
            .sortedByDescending { it.timestamp }
        RecurringPaymentEntriesDialog(
            candidate = candidate,
            entries = entries,
            onDismiss = { viewingRecurring = null }
        )
    }

    editingRecurring?.let { candidate ->
        RecurringPaymentEditDialog(
            candidate = candidate,
            categories = (transactions.map { it.category } + listOf("Bills", "Subscriptions", "Insurance", "Investments", "Other")).distinct().sorted(),
            onUpdate = { updated ->
                val key = recurringKey(candidate.merchant)
                recurringPrefs.edit()
                    .putString("merchant_$key", updated.merchant.trim())
                    .putString("amount_$key", updated.averageAmount.toString())
                    .putString("category_$key", updated.category)
                    .putInt("day_$key", updated.dayOfMonth)
                    .apply()
                recurringCandidates = recurringCandidates.map {
                    if (it.merchant == candidate.merchant) updated else it
                }
                editingRecurring = null
            },
            onDelete = {
                val key = recurringKey(candidate.merchant)
                recurringPrefs.edit().putBoolean("deleted_$key", true).apply()
                recurringCandidates = recurringCandidates.filterNot { it.merchant == candidate.merchant }
                editingRecurring = null
            },
            onDismiss = { editingRecurring = null }
        )
    }
}

@Composable
private fun YearlyMonthlyBarChart(year: Int, transactions: List<Transaction>) {
    val months = (0 until 12).map { month ->
        Calendar.getInstance().apply { set(year, month, 1, 12, 0, 0); set(Calendar.MILLISECOND, 0) }
    }
    val values = months.map { month ->
        val monthStart = Calendar.getInstance().apply {
            set(year, month.get(Calendar.MONTH), 1, 0, 0, 0); set(Calendar.MILLISECOND, 0)
        }
        val monthEnd = Calendar.getInstance().apply { timeInMillis = monthStart.timeInMillis; add(Calendar.MONTH, 1) }
        transactions.filter {
            it.type == TransactionType.DEBIT &&
                it.category != "Transfers" &&
                it.category != "Investments" &&
                it.timestamp >= monthStart.timeInMillis &&
                it.timestamp < monthEnd.timeInMillis
        }.sumOf { it.amount }
    }
    val maxValue = maxOf(1.0, values.maxOrNull() ?: 1.0)
    val chartMax = when {
        maxValue <= 1000.0 -> 1000.0
        maxValue <= 5000.0 -> 5000.0
        maxValue <= 10000.0 -> 10000.0
        maxValue <= 25000.0 -> 25000.0
        maxValue <= 50000.0 -> 50000.0
        maxValue <= 100000.0 -> 100000.0
        maxValue <= 250000.0 -> 250000.0
        else -> kotlin.math.ceil(maxValue / 100000.0) * 100000.0
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text("Monthly spending", style = MaterialTheme.typography.titleMedium)
            Text("All 12 months of $year", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(220.dp)) {
                Column(
                    Modifier.width(44.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    listOf(chartMax, chartMax * .75, chartMax * .5, chartMax * .25, 0.0).forEach {
                        Text(compactAmount(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    months.indices.forEach { i ->
                        Column(
                            Modifier.width(48.dp).fillMaxHeight(),
                            verticalArrangement = Arrangement.Bottom,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (values[i] > 0) {
                                Text(compactAmount(values[i]), style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                                Spacer(Modifier.height(4.dp))
                            } else {
                                Spacer(Modifier.height(18.dp))
                            }
                            Box(
                                Modifier.fillMaxWidth(.72f)
                                    .height((150f * (values[i] / chartMax).toFloat()).coerceAtLeast(if (values[i] > 0) 3f else 0f).dp)
                                    .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(SimpleDateFormat("MMM", Locale.getDefault()).format(months[i].time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text("12-month view • use ‹ › above to change year", Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecurringPaymentEntriesDialog(
    candidate: RecurringCandidate,
    entries: List<Transaction>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(candidate.merchant, style = MaterialTheme.typography.titleLarge)
                Text(
                    entries.size.toString() + " entries • " + currencyFormat(candidate.averageAmount) + " / month",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            if (entries.isEmpty()) {
                Text("No matching transactions found.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries) { transaction ->
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                                            .format(Date(transaction.timestamp)),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        transaction.category + " • " + bankDisplayName(transaction.sender),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    currencyFormat(transaction.amount),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun RecurringPaymentEditDialog(
    candidate: RecurringCandidate,
    categories: List<String>,
    onUpdate: (RecurringCandidate) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var merchantText by remember(candidate) { mutableStateOf(candidate.merchant) }
    var amountText by remember(candidate) { mutableStateOf(String.format(Locale.getDefault(), "%.2f", candidate.averageAmount)) }
    var category by remember(candidate) { mutableStateOf(candidate.category) }
    var dayOfMonth by remember(candidate) { mutableIntStateOf(candidate.dayOfMonth.coerceIn(1, 31)) }
    var categoryExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Recurring Payment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = merchantText,
                    onValueChange = { merchantText = it },
                    label = { Text("Name / merchant") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Monthly amount") },
                    singleLine = true
                )
                OutlinedButton(
                    onClick = {
                        val now = Calendar.getInstance()
                        now.set(Calendar.DAY_OF_MONTH, dayOfMonth.coerceAtMost(now.getActualMaximum(Calendar.DAY_OF_MONTH)))
                        DatePickerDialog(
                            context,
                            { _, _, _, selectedDay -> dayOfMonth = selectedDay },
                            now.get(Calendar.YEAR),
                            now.get(Calendar.MONTH),
                            dayOfMonth.coerceAtMost(now.getActualMaximum(Calendar.DAY_OF_MONTH))
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Recurring date: " + dayOfMonth + daySuffix(dayOfMonth) + " of each month")
                }
                Box {
                    OutlinedButton(onClick = { categoryExpanded = true }) {
                        Text("Category: $category")
                    }
                    DropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false }
                    ) {
                        categories.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item) },
                                onClick = {
                                    category = item
                                    categoryExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val amount = amountText.toDoubleOrNull()
                if (amount != null && amount > 0 && merchantText.trim().isNotBlank()) {
                    onUpdate(
                        candidate.copy(
                            merchant = merchantText.trim(),
                            averageAmount = amount,
                            category = category,
                            dayOfMonth = dayOfMonth
                        )
                    )
                }
            }) {
                Text("Update")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

private fun recurringKey(merchant: String): String =
    merchant.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')

private fun daySuffix(day: Int): String = when {
    day in 11..13 -> "th"
    day % 10 == 1 -> "st"
    day % 10 == 2 -> "nd"
    day % 10 == 3 -> "rd"
    else -> "th"
}

private fun recurringAccent(category: String): Color = when (category.lowercase(Locale.ROOT)) {
    "bills" -> Color(0xFF64B5F6)
    "insurance" -> Color(0xFFFFB74D)
    "subscriptions" -> Color(0xFFBA68C8)
    "investments" -> Color(0xFF66BB6A)
    "food" -> Color(0xFFFF8A65)
    "groceries" -> Color(0xFF26A69A)
    else -> Color(0xFF8AB4F8)
}

@Composable
private fun SpendingBarChart(
    days: List<Calendar>,
    values: List<Double>,
    spending: List<Transaction>
) {
    val barColor = MaterialTheme.colorScheme.primary
        val maxValue = maxOf(1.0, values.maxOrNull() ?: 1.0)
    val chartMax = when {
        maxValue <= 1000.0 -> 1000.0
        maxValue <= 5000.0 -> 5000.0
        maxValue <= 10000.0 -> 10000.0
        maxValue <= 25000.0 -> 25000.0
        maxValue <= 50000.0 -> 50000.0
        maxValue <= 100000.0 -> 100000.0
        maxValue <= 250000.0 -> 250000.0
        else -> kotlin.math.ceil(maxValue / 100000.0) * 100000.0
    }
    val yTicks = listOf(chartMax, chartMax * 0.75, chartMax * 0.5, chartMax * 0.25, 0.0)
    var selectedDay by remember { mutableStateOf<Int?>(null) }

    fun dayTransactions(index: Int): List<Transaction> {
        val day = days[index]
        return spending.filter {
            val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }
            c.get(Calendar.YEAR) == day.get(Calendar.YEAR) &&
                c.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
        }.sortedByDescending { it.timestamp }
    }

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
                Text(
                    "Total " + compactAmount(values.sum()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth().height(220.dp)) {
                Column(
                    Modifier.width(42.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    yTicks.forEach { tick ->
                        Text(compactAmount(tick), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(8.dp))

                val barColumn: @Composable (Int) -> Unit = { i ->
                    val day = days[i]
                    Column(
                        Modifier
                            .fillMaxHeight()
                            .then(if (days.size <= 7) Modifier.weight(1f) else Modifier.width(44.dp))
                            .clickable { selectedDay = i },
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (values[i] > 0) {
                            Text(compactAmount(values[i]), style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, softWrap = false)
                            Spacer(Modifier.height(4.dp))
                        } else {
                            Spacer(Modifier.height(18.dp))
                        }
                        Box(
                            Modifier
                                .fillMaxWidth(0.72f)
                                .height((150f * (values[i] / chartMax).toFloat())
                                    .coerceAtLeast(if (values[i] > 0) 3f else 0f).dp)
                                .background(barColor, MaterialTheme.shapes.small)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (days.size <= 7) SimpleDateFormat("EEE", Locale.getDefault()).format(day.time).take(1)
                            else SimpleDateFormat("dd", Locale.getDefault()).format(day.time),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (days.size <= 7) {
                    Row(
                        Modifier.fillMaxSize().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Bottom
                    ) { days.indices.forEach { barColumn(it) } }
                } else {
                    Row(
                        Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.Bottom
                    ) { days.indices.forEach { barColumn(it) } }
                }
            }

            Text(
                "Tap a bar to see transactions",
                Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    selectedDay?.let { index ->
        val txns = dayTransactions(index)
        val title = SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(days[index].time)
        AlertDialog(
            onDismissRequest = { selectedDay = null },
            title = { Text(title) },
            text = {
                if (txns.isEmpty()) {
                    Text("No spending on this day.")
                } else {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        Text("Total " + compactAmount(txns.sumOf { it.amount }),
                            style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        txns.forEach { tx ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(tx.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(tx.category + " • " + bankDisplayName(tx.sender),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(currencyFormat(tx.amount), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedDay = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun CategoryPieChart(
    categories: List<Pair<String, Double>>,
    total: Double,
    spending: List<Transaction>
) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val slices = categories.filter { it.second > 0 }
    val categoryColors = listOf(
        Color(0xFF4FC3F7), Color(0xFF81C784), Color(0xFFFFB74D),
        Color(0xFFE57373), Color(0xFFBA68C8), Color(0xFF4DB6AC)
    )
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Spending by category", style = MaterialTheme.typography.titleMedium)
            Text(compactAmount(total) + " total", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (slices.isEmpty()) {
                Text("No spending data for this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(150.dp)) {
                        var startAngle = -90f
                        slices.forEachIndexed { index, (_, amount) ->
                            val sweep = (amount / total * 360.0).toFloat()
                            drawArc(
                                color = categoryColors[index % categoryColors.size],
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = true
                            )
                            startAngle += sweep
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        slices.forEachIndexed { index, (name, amount) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { selectedCategory = name }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(10.dp).background(
                                    categoryColors[index % categoryColors.size],
                                    MaterialTheme.shapes.small
                                ))
                                Spacer(Modifier.width(8.dp))
                                Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(compactAmount(amount), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Tap a category for transaction details", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    selectedCategory?.let { category ->
        val txns = if (category == "Other") {
            spending.filter { it.category !in categories.take(5).map { pair -> pair.first } }
        } else {
            spending.filter { it.category == category }
        }.sortedByDescending { it.timestamp }
        AlertDialog(
            onDismissRequest = { selectedCategory = null },
            title = { Text(category) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("Total " + compactAmount(txns.sumOf { it.amount }), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    txns.forEach { tx ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(tx.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(tx.category + " • " + SimpleDateFormat("dd MMM · h:mm a", Locale.getDefault()).format(Date(tx.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(currencyFormat(tx.amount), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedCategory = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun CategoryBreakdown(
    categories: List<Pair<String, Double>>,
    total: Double,
    spending: List<Transaction>
) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }

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
                Text(compactAmount(total), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            if (categories.isEmpty()) {
                Text("No spending data for this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                categories.take(6).forEach { (name, amount) ->
                    val fraction = if (total > 0) (amount / total).toFloat() else 0f
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { selectedCategory = name }
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(compactAmount(amount), style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.fillMaxWidth().height(7.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)) {
                            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small))
                        }
                    }
                }
                Text("Tap a category for transaction details", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    selectedCategory?.let { category ->
        val txns = spending.filter { it.category == category }.sortedByDescending { it.timestamp }
        AlertDialog(
            onDismissRequest = { selectedCategory = null },
            title = { Text(category) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("Total " + compactAmount(txns.sumOf { it.amount }), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    txns.forEach { tx ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(tx.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(SimpleDateFormat("dd MMM · h:mm a", Locale.getDefault()).format(Date(tx.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(currencyFormat(tx.amount), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedCategory = null }) { Text("Close") } }
        )
    }
}

private fun currencyFormat(amount: Double): String =
    NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(amount)

@Composable
private fun HomePage(
    transactions: List<Transaction>,
    categories: List<String>,
    onScan: () -> Unit,
    onChangeCategory: (Transaction, String, Boolean) -> Unit,
    onChangeBank: (Transaction, String) -> Unit,
    onDeleteTransaction: (Transaction) -> Unit,
    onEditTransaction: (Transaction, Double, TransactionType, String, Long) -> Unit,
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
    val monthSpent = monthExpenses - monthInvested
    val selectedTransactions = selectedDay?.let { day ->
        monthTransactions.filter { transaction -> Calendar.getInstance().apply { timeInMillis = transaction.timestamp }.get(Calendar.DAY_OF_MONTH) == day }
    } ?: emptyList()
    val homeScrollState = rememberScrollState()
    val spendingSectionRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(selectedDay, selectedTransactions.size) {
        if (selectedDay != null && selectedTransactions.isNotEmpty()) {
            delay(100)
            spendingSectionRequester.bringIntoView()
        }
    }

    Column(modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).verticalScroll(homeScrollState)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("SpendSense", style = MaterialTheme.typography.headlineMedium); Text("Your money at a glance", style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = onScan) { Text("Scan SMS") }
        }
        Spacer(Modifier.height(14.dp))
        MonthHeader(displayedYear, displayedMonth, {
            if (displayedMonth == Calendar.JANUARY) { displayedMonth = Calendar.DECEMBER; displayedYear-- } else displayedMonth--
            selectedDay = null
        }, {
            if (displayedMonth == Calendar.DECEMBER) { displayedMonth = Calendar.JANUARY; displayedYear++ } else displayedMonth++
            selectedDay = null
        })
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Earned", currency.format(monthIncome), Modifier.weight(1f), Color(0xFF1B5E20), "↑")
            MetricCard("Spent", currency.format(monthSpent), Modifier.weight(1f), Color(0xFFB71C1C), "↓")
            MetricCard("Invested", currency.format(monthInvested), Modifier.weight(1f), Color(0xFF6A1B9A), "↗")
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Net cash flow", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(currency.format(monthIncome - monthExpenses), style = MaterialTheme.typography.titleMedium)
            }
            Text(monthTransactions.size.toString() + " transactions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        CalendarView(displayedYear, displayedMonth, monthTransactions, selectedDay) { selectedDay = it }


        selectedDay?.let { day ->
            Spacer(Modifier.height(14.dp))
            Text("Transactions on " + dayLabel(displayedYear, displayedMonth, day), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            if (selectedTransactions.isEmpty()) {
                Text("No transactions on this date.")
            } else {
                val spent = selectedTransactions
                    .filter { it.type == TransactionType.DEBIT && it.category != "Investments" && it.category != "Transfers" }
                    .sortedByDescending { it.timestamp }
                val earned = selectedTransactions
                    .filter { it.type == TransactionType.CREDIT }
                    .sortedByDescending { it.timestamp }
                val invested = selectedTransactions
                    .filter { it.type == TransactionType.DEBIT && it.category == "Investments" }
                    .sortedByDescending { it.timestamp }

                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (spent.isNotEmpty()) {
                        TransactionSectionHeader(
                            "💸 Spent",
                            spent.size,
                            Color(0xFFFF7043),
                            Modifier.bringIntoViewRequester(spendingSectionRequester)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            spent.forEach { transaction ->
                                TransactionRow(transaction, currency, categories, onChangeCategory, onChangeBank, onDeleteTransaction, onEditTransaction, onAddCategory)
                            }
                        }
                    }

                    if (earned.isNotEmpty()) {
                        TransactionSectionHeader("💰 Earned", earned.size, Color(0xFF43A047))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            earned.forEach { transaction ->
                                TransactionRow(transaction, currency, categories, onChangeCategory, onChangeBank, onDeleteTransaction, onEditTransaction, onAddCategory)
                            }
                        }
                    }

                    if (invested.isNotEmpty()) {
                        TransactionSectionHeader("📈 Invested", invested.size, Color(0xFF8E44AD))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            invested.forEach { transaction ->
                                TransactionRow(transaction, currency, categories, onChangeCategory, onChangeBank, onDeleteTransaction, onEditTransaction, onAddCategory)
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun TransactionSectionHeader(title: String, count: Int, accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f), MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent
        )
        Spacer(Modifier.weight(1f))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = accent
        )
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
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151F32))
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF202D46), MaterialTheme.shapes.medium)
                    .padding(vertical = 8.dp)
            ) {
                listOf("M","T","W","T","F","S","S").forEach {
                    Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(6.dp))
            for (row in 0 until cells / 7) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0..6) {
                        val day = row * 7 + col - leading + 1
                        if (day < 1 || day > daysInMonth) Spacer(Modifier.weight(1f).height(44.dp))
                        else {
                            val dayTransactions = byDay[day].orEmpty()
                            val income = dayTransactions.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount }
                            val spent = dayTransactions.filter { it.type == TransactionType.DEBIT && it.category != "Transfers" && it.category != "Investments" }.sumOf { it.amount }
                            val invested = dayTransactions.filter { it.type == TransactionType.DEBIT && it.category == "Investments" }.sumOf { it.amount }
                            val hasIncome = income > 0
                            val hasSpending = spent > 0
                            val hasInvestment = invested > 0
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
                                    Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 3.dp, vertical = 3.dp),
                                    verticalArrangement = Arrangement.spacedBy(0.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(day.toString(), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                                    if (hasIncome) {
                                        Text(
                                            "↑" + compactAmount(income),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF9BE7B5),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1
                                        )
                                    }
                                    if (hasSpending) {
                                        Text(
                                            "↓" + compactAmount(spent),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFFFFB0B0),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1
                                        )
                                    }
                                    if (hasInvestment) {
                                        Text(
                                            "↗" + compactAmount(invested),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFFFFD180),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1
                                        )
                                    }
                                    if (!hasIncome && !hasSpending && !hasInvestment) {
                                        Text("—", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("↑ income  •  ↓ spending  •  ↗ investments  •  tap a date for details", style = MaterialTheme.typography.bodySmall)
        }
    }
}

data class RecurringCandidate(
    val merchant: String,
    val category: String,
    val averageAmount: Double,
    val count: Int,
    val dayOfMonth: Int,
    val originalMerchant: String
)

private fun findRecurringCandidates(transactions: List<Transaction>): List<RecurringCandidate> {
    val cutoff = System.currentTimeMillis() - 180L * 24L * 60L * 60L * 1000L
    return transactions
        .filter { it.type == TransactionType.DEBIT && it.timestamp >= cutoff && it.merchant != "Unknown" && it.category != "Transfers" }
        .groupBy { it.merchant.trim().lowercase(Locale.ROOT) }
        .mapNotNull { (_, items) ->
            val sorted = items.sortedBy { it.timestamp }
            if (sorted.size < 3) return@mapNotNull null
            val intervals = sorted.zipWithNext().map { (a, b) -> (b.timestamp - a.timestamp).toDouble() / (24L * 60L * 60L * 1000L) }
            val monthlyIntervals = intervals.filter { it in 25.0..35.0 }
            val avg = sorted.map { it.amount }.average()
            val amountStable = sorted.all { kotlin.math.abs(it.amount - avg) / avg <= 0.15 }
            if (monthlyIntervals.size >= 2 && amountStable) {
                RecurringCandidate(
                    sorted.last().merchant,
                    sorted.last().category,
                    avg,
                    sorted.size,
                    Calendar.getInstance().apply { timeInMillis = sorted.last().timestamp }.get(Calendar.DAY_OF_MONTH),
                    sorted.last().merchant
                )
            } else null
        }
        .sortedByDescending { it.count }
}

private fun compactAmount(amount: Double): String = if (amount >= 100000) String.format(Locale.getDefault(), "%.1fL", amount / 100000) else if (amount >= 1000) String.format(Locale.getDefault(), "%.1fk", amount / 1000) else String.format(Locale.getDefault(), "%.0f", amount)
private fun dayLabel(year: Int, month: Int, day: Int): String = java.text.SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Calendar.getInstance().apply { set(year, month, day) }.time)
private fun List<Transaction>.filterByMonth(year: Int, month: Int): List<Transaction> = filter { val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }; c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month }

@Composable
private fun SpendSenseTheme(content: @Composable () -> Unit) {
    // Premium dark-finance palette: deep navy surfaces with restrained champagne/gold accents.
    val premiumDark = darkColorScheme(
        primary = Color(0xFFD8B36A),
        onPrimary = Color(0xFF17130B),
        primaryContainer = Color(0xFF3A2F19),
        onPrimaryContainer = Color(0xFFFFE8B5),
        secondary = Color(0xFF8FA9C7),
        onSecondary = Color(0xFF101820),
        secondaryContainer = Color(0xFF243447),
        onSecondaryContainer = Color(0xFFD9E8FA),
        tertiary = Color(0xFF5FC7B7),
        onTertiary = Color(0xFF06201C),
        tertiaryContainer = Color(0xFF123D37),
        onTertiaryContainer = Color(0xFFB5F1E7),
        error = Color(0xFFFF7B72),
        onError = Color(0xFF2B0907),
        errorContainer = Color(0xFF4D1A17),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF080D14),
        onBackground = Color(0xFFF1F3F5),
        surface = Color(0xFF101722),
        onSurface = Color(0xFFE8ECF1),
        surfaceVariant = Color(0xFF192331),
        onSurfaceVariant = Color(0xFFAAB5C2),
        outline = Color(0xFF394758),
        outlineVariant = Color(0xFF253140),
        inverseSurface = Color(0xFFE8ECF1),
        inverseOnSurface = Color(0xFF101722),
        inversePrimary = Color(0xFF755B24)
    )
    MaterialTheme(
        colorScheme = premiumDark,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp)
        ),
        typography = Typography(
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp)
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
    val (bg, fg) = bankBrandColors(name)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = bg
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = bankLogoMark(name),
                color = fg,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun bankLogoMark(name: String): String = when (name) {
    "HDFC" -> "HDFC"
    "ICICI" -> "ICICI"
    "SBI" -> "SBI"
    "Axis" -> "AXIS"
    "IDFC FIRST" -> "IDFC"
    "UCO" -> "UCO"
    "Kotak" -> "K"
    "Yes Bank" -> "YES"
    "IndusInd" -> "II"
    "PNB" -> "PNB"
    "Bank of Baroda" -> "BOB"
    "Canara" -> "C"
    "Federal" -> "F"
    "Paytm" -> "pay"
    "PhonePe" -> "Pe"
    "Google Pay" -> "G"
    "Amazon Pay" -> "a"
    else -> "₹"
}

private fun bankBrandColors(name: String): Pair<Color, Color> = when (name) {
    "HDFC" -> Color(0xFF004C8F) to Color.White
    "ICICI" -> Color(0xFFF58220) to Color.White
    "SBI" -> Color(0xFF114DA5) to Color.White
    "Axis" -> Color(0xFF7B1E4B) to Color.White
    "IDFC FIRST" -> Color(0xFFB51E2A) to Color.White
    "UCO" -> Color(0xFF0054A6) to Color.White
    "Kotak" -> Color(0xFFE32B2B) to Color.White
    "Yes Bank" -> Color(0xFF174A8B) to Color.White
    "IndusInd" -> Color(0xFF7B1FA2) to Color.White
    "PNB" -> Color(0xFFB5121B) to Color.White
    "Bank of Baroda" -> Color(0xFFE87722) to Color.White
    "Canara" -> Color(0xFF0072BC) to Color.White
    "Federal" -> Color(0xFF0A4C97) to Color.White
    "Paytm" -> Color(0xFF00AEEF) to Color.White
    "PhonePe" -> Color(0xFF5F259F) to Color.White
    "Google Pay" -> Color(0xFFFFFFFF) to Color(0xFF202124)
    "Amazon Pay" -> Color(0xFFFFB300) to Color(0xFF111111)
    else -> Color(0xFF2A3038) to Color.White
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

@Composable
private fun TransactionDetailDialog(transaction: Transaction, currency: NumberFormat, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(transaction.merchant) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((if (transaction.type == TransactionType.CREDIT) "+ " else "− ") + currency.format(transaction.amount), style = MaterialTheme.typography.headlineSmall)
                Text("Category: " + transaction.category)
                Text("Account: " + bankDisplayName(transaction.sender))
                Text("Date: " + SimpleDateFormat("dd MMM yyyy · h:mm a", Locale.getDefault()).format(Date(transaction.timestamp)))
                transaction.transactionRef?.let { Text("Reference: " + it) }
                Text("Confidence: " + transaction.confidence + "%")
                Text("Source: " + if (transaction.rawMessage == "Manual transaction") "Manual" else "SMS")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun EditTransactionDialog(
    transaction: Transaction,
    onDismiss: () -> Unit,
    onSave: (Double, TransactionType, String, Long) -> Unit
) {
    var amount by remember(transaction.id) { mutableStateOf(transaction.amount.toString()) }
    var merchant by remember(transaction.id) { mutableStateOf(transaction.merchant) }
    var type by remember(transaction.id) { mutableStateOf(transaction.type) }
    var selectedDate by remember(transaction.id) {
        mutableStateOf(Calendar.getInstance().apply { timeInMillis = transaction.timestamp })
    }
    val context = LocalContext.current
    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == TransactionType.DEBIT,
                        onClick = { type = TransactionType.DEBIT },
                        label = { Text("Spent") }
                    )
                    FilterChip(
                        selected = type == TransactionType.CREDIT,
                        onClick = { type = TransactionType.CREDIT },
                        label = { Text("Earned") }
                    )
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Merchant / description") },
                    singleLine = true
                )
                OutlinedButton(
                    onClick = {
                        val current = selectedDate
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                selectedDate = Calendar.getInstance().apply {
                                    set(year, month, day, current.get(Calendar.HOUR_OF_DAY), current.get(Calendar.MINUTE), current.get(Calendar.SECOND))
                                }
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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cleanAmount = amount.toDoubleOrNull()
                val cleanMerchant = merchant.trim()
                if (cleanAmount != null && cleanAmount > 0 && cleanMerchant.isNotBlank()) {
                    onSave(cleanAmount, type, cleanMerchant, selectedDate.timeInMillis)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun BankDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var bankName by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit account") },
        text = {
            OutlinedTextField(
                value = bankName,
                onValueChange = { bankName = it },
                label = { Text("Bank / Cash") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(bankName.trim().ifBlank { "Cash" }) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}



@Composable
private fun CategoryBudgetDialog(
    categories: List<String>,
    prefs: android.content.SharedPreferences,
    year: Int,
    month: Int,
    spending: List<Transaction>,
    onDismiss: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf(categories.firstOrNull() ?: "Food") }
    var amount by remember { mutableStateOf("") }
    val current = prefs.getFloat("category_" + year + "_" + month + "_" + selectedCategory, 0f).toDouble()
    LaunchedEffect(selectedCategory) { amount = if (current > 0) current.toString() else "" }
    val spent = spending.filter { it.category == selectedCategory }.sumOf { it.amount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category budget") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Category: " + selectedCategory)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.take(8).forEach { category ->
                        FilterChip(selectedCategory == category, { selectedCategory = category }, label = { Text(category) })
                    }
                }
                OutlinedTextField(amount, { amount = it }, label = { Text("Monthly limit") }, prefix = { Text("₹") }, singleLine = true)
                if (current > 0) Text("Spent: " + currencyFormat(spent), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                amount.toDoubleOrNull()?.takeIf { it > 0 }?.let {
                    prefs.edit().putFloat("category_" + year + "_" + month + "_" + selectedCategory, it.toFloat()).apply()
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun BudgetDialog(
    current: Double,
    title: String,
    onDismiss: () -> Unit,
    onSave: (Double, Boolean) -> Unit
) {
    var amount by remember { mutableStateOf(if (current > 0) current.toString() else "") }
    var allMonths by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎯 " + title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Target amount") },
                    prefix = { Text("₹") },
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allMonths, onCheckedChange = { allMonths = it })
                    Text("Use this target for all months")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                amount.toDoubleOrNull()?.takeIf { it > 0 }?.let { onSave(it, allMonths) }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


@Composable
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier,
    accent: Color,
    icon: String
) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.16f))
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent
                )
                Text(icon, color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
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
    onEditTransaction: (Transaction, Double, TransactionType, String, Long) -> Unit,
    onAddCategory: (String) -> Unit
) {
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var editMenuExpanded by remember { mutableStateOf(false) }
    var confirmExpanded by remember { mutableStateOf(false) }
    var addCategoryExpanded by remember { mutableStateOf(false) }
    var bankDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var editDialog by remember { mutableStateOf(false) }
    var detailsDialog by remember { mutableStateOf(false) }
    var selectedCategory by remember(transaction.category) { mutableStateOf(transaction.category) }
    var newCategory by remember { mutableStateOf("") }

    Card(
        onClick = { detailsDialog = true },
        modifier = Modifier.fillMaxWidth(),
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
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box {
                        TextButton(
                            onClick = { categoryMenuExpanded = true },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) { Text("Category", fontSize = 12.sp) }

                        DropdownMenu(
                            expanded = categoryMenuExpanded,
                            onDismissRequest = { categoryMenuExpanded = false }
                        ) {
                            categories.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category) },
                                    onClick = {
                                        selectedCategory = category
                                        categoryMenuExpanded = false
                                        confirmExpanded = true
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("New category…") },
                                onClick = {
                                    categoryMenuExpanded = false
                                    addCategoryExpanded = true
                                }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier.wrapContentSize(Alignment.TopEnd)
                    ) {
                        TextButton(
                            onClick = { editMenuExpanded = true },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) { Text("Edit", fontSize = 12.sp) }

                        DropdownMenu(
                            expanded = editMenuExpanded,
                            onDismissRequest = { editMenuExpanded = false },
                            offset = DpOffset(0.dp, 4.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit transaction") },
                                onClick = {
                                    editMenuExpanded = false
                                    editDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Change account") },
                                onClick = {
                                    editMenuExpanded = false
                                    bankDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    editMenuExpanded = false
                                    deleteDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (detailsDialog) {
        TransactionDetailDialog(transaction, currency, onDismiss = { detailsDialog = false })
    }

    if (editDialog) {
        EditTransactionDialog(
            transaction = transaction,
            onDismiss = { editDialog = false },
            onSave = { amount, type, merchant, timestamp ->
                editDialog = false
                onEditTransaction(transaction, amount, type, merchant, timestamp)
            }
        )
    }

    if (bankDialog) {
        BankDialog(
            current = bankDisplayName(transaction.sender),
            onDismiss = { bankDialog = false },
            onSave = { bank ->
                bankDialog = false
                onChangeBank(transaction, bank)
            }
        )
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("Delete transaction?") },
            text = { Text("This transaction will be removed from SpendSense.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteDialog = false
                    onDeleteTransaction(transaction)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } }
        )
    }

    if (confirmExpanded) {
        AlertDialog(
            onDismissRequest = { confirmExpanded = false },
            title = { Text("Apply category?") },
            text = {
                Text("Apply “$selectedCategory” to this transaction only, or remember it for future transactions from this merchant?")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmExpanded = false
                    onChangeCategory(transaction, selectedCategory, true)
                }) { Text("Remember") }
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
