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
import com.spendsense.app.data.CategoryEntity
import com.spendsense.app.data.TransactionEntity
import com.spendsense.app.data.TransactionRepository
import com.spendsense.app.data.toTransaction
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val repository by lazy { TransactionRepository.get(this) }
    private var transactions by mutableStateOf<List<Transaction>>(emptyList())
    private var categories by mutableStateOf<List<CategoryEntity>>(emptyList())
    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) scanSms()
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
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED) scanSms()
        else smsPermissionLauncher.launch(Manifest.permission.READ_SMS)
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
        Text("Detected transactions (" + transactions.size + ")", style = MaterialTheme.typography.titleLarge)

        if (transactions.isEmpty()) {
            Text("No transactions scanned yet. Tap the button above and grant SMS access.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(transactions.take(100), key = { it.id }) { TransactionRow(it, currency, categories, onChangeCategory, onAddCategory) }
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
