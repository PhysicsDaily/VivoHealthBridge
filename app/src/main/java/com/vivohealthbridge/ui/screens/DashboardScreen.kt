package com.vivohealthbridge.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vivohealthbridge.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onRequestPermissions: () -> Unit,
    onNavigateToManualEntry: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.checkStatus() }

    LaunchedEffect(uiState.syncResult, uiState.error) {
        uiState.syncResult?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSyncResult()
        }
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSyncResult()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("VivoHealthBridge") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Warnings
            if (!uiState.isAccessibilityEnabled) {
                WarningCard(
                    message = "Accessibility Service is not enabled. Auto-sync won't work.",
                    buttonText = "Enable in Settings",
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (!uiState.isHealthConnectAvailable) {
                WarningCard(
                    message = "Health Connect is not available. Please install it from Play Store.",
                    buttonText = "Install",
                    onClick = { /* Open Play Store */ }
                )
                Spacer(modifier = Modifier.height(8.dp))
            } else if (!uiState.healthConnectPermissionsGranted) {
                WarningCard(
                    message = "Health Connect permissions not granted.",
                    buttonText = "Grant Permissions",
                    onClick = onRequestPermissions
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Sync Button
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Syncing from Vivo Health...", style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Button(
                            onClick = { viewModel.startAutoSync(context) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = uiState.isAccessibilityEnabled && uiState.healthConnectPermissionsGranted
                        ) {
                            Icon(Icons.Filled.Sync, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Auto-Sync from Vivo Health", fontSize = 16.sp)
                        }
                    }

                    uiState.lastSyncTime?.let { time ->
                        Spacer(modifier = Modifier.height(8.dp))
                        val fmt = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                        Text(
                            "Last sync: ${fmt.format(Date(time))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Last synced data cards
            uiState.lastSyncedData?.let { data ->
                Text(
                    "Last Synced Data",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    data.heartRateBpm?.let { hr ->
                        item {
                            MetricCard(
                                emoji = "❤️",
                                title = "Heart Rate",
                                value = "$hr bpm",
                                subtitle = data.restingHeartRateBpm?.let { "Resting: $it" }
                            )
                        }
                    }
                    data.sleepTotalMinutes?.let { sleep ->
                        item {
                            MetricCard(
                                emoji = "😴",
                                title = "Sleep",
                                value = "${sleep / 60}h ${sleep % 60}m",
                                subtitle = listOfNotNull(
                                    data.deepSleepMinutes?.let { "D:${it / 60}h${it % 60}m" },
                                    data.lightSleepMinutes?.let { "L:${it / 60}h${it % 60}m" },
                                    data.remSleepMinutes?.let { "R:${it / 60}h${it % 60}m" }
                                ).joinToString(" ")
                            )
                        }
                    }
                    data.oxygenSaturation?.let { spo2 ->
                        item {
                            MetricCard(emoji = "🫁", title = "SpO2", value = "$spo2%")
                        }
                    }
                    data.stressLevel?.let { stress ->
                        item {
                            MetricCard(
                                emoji = "🧠",
                                title = "Stress",
                                value = "$stress",
                                subtitle = data.stressCategory
                            )
                        }
                    }
                    data.weightKg?.let { weight ->
                        item {
                            MetricCard(emoji = "⚖️", title = "Weight", value = "$weight kg")
                        }
                    }
                    data.steps?.let { steps ->
                        item {
                            MetricCard(emoji = "🚶", title = "Steps", value = "$steps")
                        }
                    }
                }
            } ?: run {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "No data synced yet.\nTap 'Auto-Sync' or use Manual Entry.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
            }

            // Manual entry shortcut
            OutlinedButton(
                onClick = onNavigateToManualEntry,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("✍️ Manual Entry")
            }
        }
    }
}

@Composable
private fun WarningCard(message: String, buttonText: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3D2C00))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Warning, null, tint = Color(0xFFFFB74D), modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB74D))
            }
            TextButton(onClick = onClick) {
                Text(buttonText, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MetricCard(emoji: String, title: String, value: String, subtitle: String? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(emoji, fontSize = 24.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
