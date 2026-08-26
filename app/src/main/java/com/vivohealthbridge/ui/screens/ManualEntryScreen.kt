package com.vivohealthbridge.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vivohealthbridge.data.models.DailyActivity
import com.vivohealthbridge.data.models.ParsedHealthData
import com.vivohealthbridge.data.models.SleepDetail
import com.vivohealthbridge.viewmodel.MainViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryScreen(viewModel: MainViewModel) {
    val scrollState = rememberScrollState()
    var showSuccess by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text("Manual Entry", style = MaterialTheme.typography.headlineMedium)
        Text("Enter values from Vivo Health app", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(16.dp))

        showSuccess?.let { msg ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(msg)
                }
            }
        }

        // Heart Rate
        EntrySection(title = "❤️ Heart Rate") {
            var bpm by remember { mutableStateOf("") }
            var isResting by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = bpm, onValueChange = { bpm = it.filter { c -> c.isDigit() } },
                label = { Text("BPM") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isResting, onCheckedChange = { isResting = it })
                Text("Resting heart rate")
            }
            Button(
                onClick = {
                    val value = bpm.toIntOrNull() ?: return@Button
                    val data = if (isResting) ParsedHealthData(restingHeartRateBpm = value)
                    else ParsedHealthData(heartRateBpm = value)
                    viewModel.syncManualEntry(data)
                    showSuccess = "Heart rate: $bpm bpm synced!"; bpm = ""
                },
                enabled = bpm.isNotEmpty() && (bpm.toIntOrNull() ?: 0) in 30..250,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Heart Rate") }
        }

        // Sleep
        EntrySection(title = "😴 Sleep") {
            var startHour by remember { mutableStateOf("22") }
            var startMin by remember { mutableStateOf("00") }
            var endHour by remember { mutableStateOf("07") }
            var endMin by remember { mutableStateOf("00") }
            var deepH by remember { mutableStateOf("") }
            var deepM by remember { mutableStateOf("") }
            var lightH by remember { mutableStateOf("") }
            var lightM by remember { mutableStateOf("") }
            var remH by remember { mutableStateOf("") }
            var remM by remember { mutableStateOf("") }

            Text("Sleep Time", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = startHour, onValueChange = { startHour = it.take(2).filter { c -> c.isDigit() } },
                    label = { Text("Start Hr") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = startMin, onValueChange = { startMin = it.take(2).filter { c -> c.isDigit() } },
                    label = { Text("Start Min") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = endHour, onValueChange = { endHour = it.take(2).filter { c -> c.isDigit() } },
                    label = { Text("End Hr") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = endMin, onValueChange = { endMin = it.take(2).filter { c -> c.isDigit() } },
                    label = { Text("End Min") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Sleep Stages (optional)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = deepH, onValueChange = { deepH = it.filter { c -> c.isDigit() } },
                    label = { Text("Deep h") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = deepM, onValueChange = { deepM = it.filter { c -> c.isDigit() } },
                    label = { Text("Deep m") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = lightH, onValueChange = { lightH = it.filter { c -> c.isDigit() } },
                    label = { Text("Light h") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = lightM, onValueChange = { lightM = it.filter { c -> c.isDigit() } },
                    label = { Text("Light m") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = remH, onValueChange = { remH = it.filter { c -> c.isDigit() } },
                    label = { Text("REM h") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = remM, onValueChange = { remM = it.filter { c -> c.isDigit() } },
                    label = { Text("REM m") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.weight(1f))
            }

            Button(
                onClick = {
                    val sH = startHour.toIntOrNull() ?: 22; val sM = startMin.toIntOrNull() ?: 0
                    val eH = endHour.toIntOrNull() ?: 7; val eM = endMin.toIntOrNull() ?: 0
                    val totalMins = if (eH < sH) (24 - sH + eH) * 60 + (eM - sM) else (eH - sH) * 60 + (eM - sM)
                    val data = ParsedHealthData(
                        sleep = SleepDetail(
                            totalMinutes = totalMins,
                            bedTime = String.format(Locale.US, "%02d:%02d", sH, sM),
                            wakeTime = String.format(Locale.US, "%02d:%02d", eH, eM),
                            deepMinutes = ((deepH.toIntOrNull() ?: 0) * 60 + (deepM.toIntOrNull() ?: 0)).takeIf { it > 0 },
                            lightMinutes = ((lightH.toIntOrNull() ?: 0) * 60 + (lightM.toIntOrNull() ?: 0)).takeIf { it > 0 },
                            remMinutes = ((remH.toIntOrNull() ?: 0) * 60 + (remM.toIntOrNull() ?: 0)).takeIf { it > 0 }
                        )
                    )
                    viewModel.syncManualEntry(data)
                    showSuccess = "Sleep: ${totalMins / 60}h ${totalMins % 60}m synced!"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Sleep") }
        }

        // SpO2
        EntrySection(title = "🫁 SpO2") {
            var spo2 by remember { mutableStateOf("") }
            OutlinedTextField(value = spo2, onValueChange = { spo2 = it.filter { c -> c.isDigit() } },
                label = { Text("Oxygen Saturation %") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button(
                onClick = {
                    val v = spo2.toIntOrNull() ?: return@Button
                    viewModel.syncManualEntry(ParsedHealthData(oxygenSaturation = v))
                    showSuccess = "SpO2: $v% synced!"; spo2 = ""
                },
                enabled = spo2.isNotEmpty() && (spo2.toIntOrNull() ?: 0) in 70..100,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save SpO2") }
        }

        // Stress
        EntrySection(title = "🧠 Stress") {
            var stress by remember { mutableStateOf("") }
            val level = stress.toIntOrNull()
            val category = when {
                level == null -> ""
                level in 1..33 -> "Relaxed"
                level in 34..66 -> "Moderate"
                level in 67..100 -> "High"
                else -> ""
            }

            OutlinedTextField(value = stress, onValueChange = { stress = it.filter { c -> c.isDigit() } },
                label = { Text("Stress Level (0-100)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { if (category.isNotEmpty()) Text("Category: $category") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button(
                onClick = {
                    viewModel.syncManualEntry(ParsedHealthData(stressLevel = level, stressCategory = category))
                    showSuccess = "Stress: $level ($category) synced!"; stress = ""
                },
                enabled = level != null && level in 0..100,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Stress") }
        }

        // Steps
        EntrySection(title = "🚶 Steps") {
            var steps by remember { mutableStateOf("") }
            OutlinedTextField(value = steps, onValueChange = { steps = it.filter { c -> c.isDigit() } },
                label = { Text("Step Count") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button(
                onClick = {
                    val v = steps.toLongOrNull() ?: return@Button
                    viewModel.syncManualEntry(ParsedHealthData(activity = DailyActivity(steps = v)))
                    showSuccess = "Steps: $v synced!"; steps = ""
                },
                enabled = steps.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Steps") }
        }

        // Weight
        EntrySection(title = "⚖️ Weight") {
            var weight by remember { mutableStateOf("") }
            OutlinedTextField(value = weight, onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Weight (kg)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Button(
                onClick = {
                    val v = weight.toFloatOrNull() ?: return@Button
                    viewModel.syncManualEntry(ParsedHealthData(weightKg = v))
                    showSuccess = "Weight: $v kg synced!"; weight = ""
                },
                enabled = weight.toFloatOrNull() != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Weight") }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun EntrySection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}
