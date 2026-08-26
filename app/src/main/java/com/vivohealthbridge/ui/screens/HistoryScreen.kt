package com.vivohealthbridge.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vivohealthbridge.data.models.SyncStatus
import com.vivohealthbridge.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: MainViewModel) {
    val records by viewModel.syncHistory.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear History?") },
            text = { Text("This will delete all sync records. Data already in Health Connect will not be affected.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearHistory(); showClearDialog = false }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync History") },
                actions = {
                    if (records.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Filled.Delete, "Clear history")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (records.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No sync history yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(records, key = { it.id }) { record ->
                    val emoji = when (record.metricType) {
                        "HEART_RATE" -> "❤️"
                        "SLEEP" -> "😴"
                        "SPO2" -> "🫁"
                        "STRESS" -> "🧠"
                        "STEPS" -> "🚶"
                        "WEIGHT" -> "⚖️"
                        "EXERCISE" -> "🏃"
                        else -> "📊"
                    }
                    val statusColor = when (record.status) {
                        SyncStatus.SUCCESS.name -> Color(0xFF4CAF50)
                        SyncStatus.FAILED.name -> Color(0xFFF44336)
                        else -> Color(0xFFFF9800)
                    }
                    val fmt = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(emoji, modifier = Modifier.padding(end = 12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(record.metricType.replace("_", " "),
                                    style = MaterialTheme.typography.labelMedium)
                                Text(record.value, style = MaterialTheme.typography.bodyLarge)
                                Text(fmt.format(Date(record.syncedAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = statusColor.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    record.status,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = statusColor,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
