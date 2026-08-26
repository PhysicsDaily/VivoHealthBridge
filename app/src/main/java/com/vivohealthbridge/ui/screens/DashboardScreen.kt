package com.vivohealthbridge.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
import com.vivohealthbridge.data.models.DailyActivity
import com.vivohealthbridge.data.models.SleepDetail
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
    val progress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // The automation runs in the accessibility service and the Health Connect
    // writes run here, so a sync is "in flight" while either one is busy.
    val syncing = uiState.isSyncing || progress.active

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

            // ── Sync control ──────────────────────────────────────
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
                    if (syncing) {
                        if (progress.percent > 0) {
                            LinearProgressIndicator(
                                progress = { progress.percent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(progress.step, style = MaterialTheme.typography.bodyLarge)
                        if (progress.detail.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                progress.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.cancelSync() }) { Text("Cancel") }
                    } else {
                        Button(
                            onClick = { viewModel.startAutoSync(context) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = uiState.isAccessibilityEnabled
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

            val data = uiState.lastSyncedData
            if (data == null) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "No data synced yet.\nTap 'Auto-Sync' or use Manual Entry.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // ══ Activity rings — read first, top of the Vivo home ══
                    data.activity?.takeIf { it.hasData() }?.let { activity ->
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Activity") }
                        activityCards(activity)
                    }

                    // ══ Sleep — the detail screen, in on-screen order ══════
                    data.sleep?.takeIf { it.hasData() }?.let { sleep ->
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Sleep") }
                        sleepCards(sleep)
                    }

                    // ══ Single metrics (manual entry) ══════════════════════
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
                    data.oxygenSaturation?.let {
                        item { MetricCard(emoji = "🫁", title = "SpO₂", value = "$it%") }
                    }
                    data.stressLevel?.let {
                        item {
                            MetricCard(
                                emoji = "🧠",
                                title = "Stress",
                                value = "$it",
                                subtitle = data.stressCategory
                            )
                        }
                    }
                    data.weightKg?.let {
                        item { MetricCard(emoji = "⚖️", title = "Weight", value = "$it kg") }
                    }

                    if (uiState.notWritten.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            NotWrittenCard(uiState.notWritten)
                        }
                    }
                }
            }

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

// ══════════════════════════════════════════════════════════════════
//  Card groups, in the order the Vivo app shows them
// ══════════════════════════════════════════════════════════════════

private fun LazyGridScope.activityCards(
    activity: DailyActivity,
) {
    activity.steps?.let { steps ->
        item {
            MetricCard(
                emoji = "🚶",
                title = "Steps",
                value = "$steps",
                subtitle = activity.stepsGoal?.let { "Goal $it" }
            )
        }
    }
    activity.exerciseMinutes?.let { minutes ->
        item {
            MetricCard(
                emoji = "🏃",
                title = "Exercise",
                value = "$minutes min",
                subtitle = activity.exerciseGoalMinutes?.let { "Goal $it min · app only" }
                    ?: "app only"
            )
        }
    }
    activity.activeCalories?.let { calories ->
        item {
            MetricCard(
                emoji = "🔥",
                title = "Calories",
                value = "$calories kcal",
                subtitle = activity.activeCaloriesGoal?.let { "Goal $it kcal" }
            )
        }
    }
    activity.standHours?.let { hours ->
        item {
            MetricCard(
                emoji = "🧍",
                title = "Stand",
                value = "$hours hr",
                subtitle = activity.standGoalHours?.let { "Goal $it hr · app only" } ?: "app only"
            )
        }
    }
    activity.distanceKm?.let { km ->
        item { MetricCard(emoji = "📍", title = "Distance", value = "$km km") }
    }
}

private fun LazyGridScope.sleepCards(sleep: SleepDetail) {
    // 1 · total duration + the hypnogram window
    sleep.totalMinutes?.let { total ->
        item {
            MetricCard(
                emoji = "😴",
                title = "Total sleep",
                value = total.asDuration(),
                subtitle = if (sleep.bedTime != null && sleep.wakeTime != null) {
                    "${sleep.bedTime} – ${sleep.wakeTime}"
                } else {
                    null
                }
            )
        }
    }

    // 2 · the sliding vitals cards
    sleep.heartRate?.let {
        item { MetricCard(emoji = "💓", title = "Sleep HR", value = it.format("bpm")) }
    }
    sleep.respiratoryRate?.let {
        item { MetricCard(emoji = "🫁", title = "Respiratory", value = it.format("/min")) }
    }
    sleep.spo2?.let {
        item {
            MetricCard(
                emoji = "🩸",
                title = "Sleep SpO₂",
                value = it.format("%"),
                subtitle = sleep.averageSpo2?.let { avg -> "Avg $avg%" }
            )
        }
    }
    sleep.hrv?.let {
        item { MetricCard(emoji = "📈", title = "Sleep HRV", value = it.format("ms")) }
    }

    // 3 · the score block
    sleep.score?.let { score ->
        item {
            MetricCard(
                emoji = "🏅",
                title = "Sleep score",
                value = "$score pts",
                subtitle = sleep.scoreLabel
            )
        }
    }

    // 4 · "More analysis"
    if (sleep.deepMinutes != null || sleep.lightMinutes != null || sleep.remMinutes != null) {
        item {
            MetricCard(
                emoji = "📊",
                title = "Stages",
                value = listOfNotNull(
                    sleep.deepMinutes?.let { "Deep ${it.asDuration()}" },
                    sleep.lightMinutes?.let { "Light ${it.asDuration()}" },
                    sleep.remMinutes?.let { "REM ${it.asDuration()}" },
                ).joinToString("\n"),
                valueStyleSmall = true,
                subtitle = listOfNotNull(
                    sleep.deepPercent?.let { "D $it%" },
                    sleep.lightPercent?.let { "L $it%" },
                    sleep.remPercent?.let { "R $it%" },
                ).joinToString("  ").ifBlank { null }
                    ?.let { if (sleep.stagesDerived) "$it (derived)" else it }
            )
        }
    }
    if (sleep.deepRating != null || sleep.lightRating != null || sleep.remRating != null) {
        item {
            MetricCard(
                emoji = "⚖️",
                title = "Proportions",
                value = listOfNotNull(
                    sleep.deepRating?.let { "Deep $it" },
                    sleep.lightRating?.let { "Light $it" },
                    sleep.remRating?.let { "REM $it" },
                ).joinToString("\n"),
                valueStyleSmall = true,
            )
        }
    }
    sleep.awakenings?.let { count ->
        item {
            MetricCard(
                emoji = "👁️",
                title = "Awakenings",
                value = "$count×",
                subtitle = sleep.awakeMinutes?.let { "${it.asDuration()} awake" }
            )
        }
    }
    sleep.continuityScore?.let { score ->
        item {
            MetricCard(
                emoji = "🔗",
                title = "Deep continuity",
                value = "$score pts",
                subtitle = sleep.continuityRating
            )
        }
    }
}

private fun Int.asDuration(): String =
    if (this >= 60) "${this / 60}h ${this % 60}m" else "${this}m"

// ══════════════════════════════════════════════════════════════════
//  Pieces
// ══════════════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun NotWrittenCard(items: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    "Read, but not sent to Health Connect",
                    style = MaterialTheme.typography.labelMedium
                )
                items.forEach {
                    Text(
                        "• $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
private fun MetricCard(
    emoji: String,
    title: String,
    value: String,
    subtitle: String? = null,
    valueStyleSmall: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(emoji, fontSize = 24.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(
                value,
                style = if (valueStyleSmall) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                fontWeight = FontWeight.Bold
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
