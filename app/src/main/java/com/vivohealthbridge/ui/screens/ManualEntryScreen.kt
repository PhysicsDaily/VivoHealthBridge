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
import com.vivohealthbridge.data.models.HeartRateDetail
import com.vivohealthbridge.data.models.MetricRange
import com.vivohealthbridge.data.models.OxygenSaturationDetail
import com.vivohealthbridge.data.models.ParsedHealthData
import com.vivohealthbridge.data.models.SleepDetail
import com.vivohealthbridge.data.models.StressDetail
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
            var minBpm by remember { mutableStateOf("") }
            var maxBpm by remember { mutableStateOf("") }
            var restingBpm by remember { mutableStateOf("") }
            var currentBpm by remember { mutableStateOf("") }
            var walkingBpm by remember { mutableStateOf("") }
            var sleepingBpm by remember { mutableStateOf("") }

            Text("Daily Range (BPM)", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minBpm, onValueChange = { minBpm = it.filter { c -> c.isDigit() } },
                    label = { Text("Min BPM") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f), singleLine = true
                )
                OutlinedTextField(
                    value = maxBpm, onValueChange = { maxBpm = it.filter { c -> c.isDigit() } },
                    label = { Text("Max BPM") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f), singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = restingBpm, onValueChange = { restingBpm = it.filter { c -> c.isDigit() } },
                    label = { Text("Resting BPM") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f), singleLine = true
                )
                OutlinedTextField(
                    value = currentBpm, onValueChange = { currentBpm = it.filter { c -> c.isDigit() } },
                    label = { Text("Current BPM") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f), singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = walkingBpm, onValueChange = { walkingBpm = it.filter { c -> c.isDigit() } },
                    label = { Text("Walking BPM") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f), singleLine = true
                )
                OutlinedTextField(
                    value = sleepingBpm, onValueChange = { sleepingBpm = it.filter { c -> c.isDigit() } },
                    label = { Text("Sleeping BPM") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f), singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val min = minBpm.toIntOrNull()
                    val max = maxBpm.toIntOrNull()
                    val range = if (min != null && max != null) MetricRange(min, max) else null
                    val rest = restingBpm.toIntOrNull()
                    val curr = currentBpm.toIntOrNull()
                    val walk = walkingBpm.toIntOrNull()
                    val sleep = sleepingBpm.toIntOrNull()
                    val data = ParsedHealthData(
                        heartRate = HeartRateDetail(
                            range = range,
                            restingBpm = rest,
                            currentBpm = curr,
                            walkingBpm = walk,
                            sleepingBpm = sleep
                        ),
                        restingHeartRateBpm = rest,
                        heartRateBpm = curr ?: range?.min
                    )
                    viewModel.syncManualEntry(data)
                    showSuccess = "Heart rate synced!"
                    minBpm = ""; maxBpm = ""; restingBpm = ""; currentBpm = ""; walkingBpm = ""; sleepingBpm = ""
                },
                enabled = (minBpm.isNotEmpty() && maxBpm.isNotEmpty()) || restingBpm.isNotEmpty() || currentBpm.isNotEmpty() || walkingBpm.isNotEmpty() || sleepingBpm.isNotEmpty(),
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
            var awakeM by remember { mutableStateOf("") }
            var awakeningsCount by remember { mutableStateOf("") }
            var sleepScore by remember { mutableStateOf("") }
            var sleepHrMin by remember { mutableStateOf("") }
            var sleepHrMax by remember { mutableStateOf("") }
            var sleepSpo2Min by remember { mutableStateOf("") }
            var sleepSpo2Max by remember { mutableStateOf("") }
            var sleepAvgSpo2 by remember { mutableStateOf("") }
            var sleepRrMin by remember { mutableStateOf("") }
            var sleepRrMax by remember { mutableStateOf("") }
            var sleepHrvMin by remember { mutableStateOf("") }
            var sleepHrvMax by remember { mutableStateOf("") }
            var continuityScore by remember { mutableStateOf("") }

            Text("Sleep Window", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = startHour, onValueChange = { startHour = it.take(2).filter { c -> c.isDigit() } },
                    label = { Text("Bed Hr") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = startMin, onValueChange = { startMin = it.take(2).filter { c -> c.isDigit() } },
                    label = { Text("Bed Min") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = endHour, onValueChange = { endHour = it.take(2).filter { c -> c.isDigit() } },
                    label = { Text("Wake Hr") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = endMin, onValueChange = { endMin = it.take(2).filter { c -> c.isDigit() } },
                    label = { Text("Wake Min") }, modifier = Modifier.weight(1f), singleLine = true,
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
                OutlinedTextField(value = awakeM, onValueChange = { awakeM = it.filter { c -> c.isDigit() } },
                    label = { Text("Awake m") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = awakeningsCount, onValueChange = { awakeningsCount = it.filter { c -> c.isDigit() } },
                    label = { Text("Awakenings") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Sleep Vitals (optional)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = sleepHrMin, onValueChange = { sleepHrMin = it.filter { c -> c.isDigit() } },
                    label = { Text("HR Min") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = sleepHrMax, onValueChange = { sleepHrMax = it.filter { c -> c.isDigit() } },
                    label = { Text("HR Max") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = sleepSpo2Min, onValueChange = { sleepSpo2Min = it.filter { c -> c.isDigit() } },
                    label = { Text("SpO₂ Min") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = sleepSpo2Max, onValueChange = { sleepSpo2Max = it.filter { c -> c.isDigit() } },
                    label = { Text("SpO₂ Max") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = sleepAvgSpo2, onValueChange = { sleepAvgSpo2 = it.filter { c -> c.isDigit() } },
                    label = { Text("Avg SpO₂ %") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = sleepScore, onValueChange = { sleepScore = it.filter { c -> c.isDigit() } },
                    label = { Text("Score (pts)") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = continuityScore, onValueChange = { continuityScore = it.filter { c -> c.isDigit() } },
                    label = { Text("Continuity") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = sleepRrMin, onValueChange = { sleepRrMin = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Resp Min") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(value = sleepRrMax, onValueChange = { sleepRrMax = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Resp Max") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(value = sleepHrvMin, onValueChange = { sleepHrvMin = it.filter { c -> c.isDigit() } },
                    label = { Text("HRV Min") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = sleepHrvMax, onValueChange = { sleepHrvMax = it.filter { c -> c.isDigit() } },
                    label = { Text("HRV Max") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val sH = startHour.toIntOrNull() ?: 22; val sM = startMin.toIntOrNull() ?: 0
                    val eH = endHour.toIntOrNull() ?: 7; val eM = endMin.toIntOrNull() ?: 0
                    val startMins = sH * 60 + sM
                    val endMins = eH * 60 + eM
                    val diff = if (endMins >= startMins) endMins - startMins else endMins + 24 * 60 - startMins
                    val totalMins = if (diff == 0) 24 * 60 else diff

                    val hrMin = sleepHrMin.toIntOrNull()
                    val hrMax = sleepHrMax.toIntOrNull()
                    val hrRange = if (hrMin != null && hrMax != null) MetricRange(hrMin, hrMax) else null

                    val rrMin = sleepRrMin.toFloatOrNull()
                    val rrMax = sleepRrMax.toFloatOrNull()
                    val rrRange = if (rrMin != null && rrMax != null) MetricRange(rrMin, rrMax) else null

                    val spo2Min = sleepSpo2Min.toIntOrNull()
                    val spo2Max = sleepSpo2Max.toIntOrNull()
                    val spo2Range = if (spo2Min != null && spo2Max != null) MetricRange(spo2Min, spo2Max) else null

                    val hrvMin = sleepHrvMin.toIntOrNull()
                    val hrvMax = sleepHrvMax.toIntOrNull()
                    val hrvRange = if (hrvMin != null && hrvMax != null) MetricRange(hrvMin, hrvMax) else null

                    val data = ParsedHealthData(
                        sleep = SleepDetail(
                            totalMinutes = totalMins,
                            bedTime = String.format(Locale.US, "%02d:%02d", sH, sM),
                            wakeTime = String.format(Locale.US, "%02d:%02d", eH, eM),
                            heartRate = hrRange,
                            respiratoryRate = rrRange,
                            spo2 = spo2Range,
                            hrv = hrvRange,
                            score = sleepScore.toIntOrNull(),
                            deepMinutes = ((deepH.toIntOrNull() ?: 0) * 60 + (deepM.toIntOrNull() ?: 0)).takeIf { it > 0 },
                            lightMinutes = ((lightH.toIntOrNull() ?: 0) * 60 + (lightM.toIntOrNull() ?: 0)).takeIf { it > 0 },
                            remMinutes = ((remH.toIntOrNull() ?: 0) * 60 + (remM.toIntOrNull() ?: 0)).takeIf { it > 0 },
                            awakeMinutes = awakeM.toIntOrNull(),
                            awakenings = awakeningsCount.toIntOrNull(),
                            continuityScore = continuityScore.toIntOrNull(),
                            averageSpo2 = sleepAvgSpo2.toIntOrNull(),
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
            var spo2Min by remember { mutableStateOf("") }
            var spo2Max by remember { mutableStateOf("") }
            var avgSpo2 by remember { mutableStateOf("") }
            var sleepAvgSpo2 by remember { mutableStateOf("") }
            var currentSpo2 by remember { mutableStateOf("") }

            Text("Daily Range (%)", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = spo2Min, onValueChange = { spo2Min = it.filter { c -> c.isDigit() } },
                    label = { Text("Min %") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = spo2Max, onValueChange = { spo2Max = it.filter { c -> c.isDigit() } },
                    label = { Text("Max %") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = avgSpo2, onValueChange = { avgSpo2 = it.filter { c -> c.isDigit() } },
                    label = { Text("Average %") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = sleepAvgSpo2, onValueChange = { sleepAvgSpo2 = it.filter { c -> c.isDigit() } },
                    label = { Text("Sleep Avg %") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = currentSpo2, onValueChange = { currentSpo2 = it.filter { c -> c.isDigit() } },
                label = { Text("Current SpO₂ %") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val min = spo2Min.toIntOrNull()
                    val max = spo2Max.toIntOrNull()
                    val range = if (min != null && max != null) MetricRange(min, max) else null
                    val avg = avgSpo2.toIntOrNull()
                    val sleepAvg = sleepAvgSpo2.toIntOrNull()
                    val curr = currentSpo2.toIntOrNull() ?: avg ?: max ?: min
                    val data = ParsedHealthData(
                        oxygenSaturation = OxygenSaturationDetail(
                            range = range,
                            average = avg,
                            averageSleep = sleepAvg,
                            current = curr
                        )
                    )
                    viewModel.syncManualEntry(data)
                    showSuccess = "SpO2 synced!"
                    spo2Min = ""; spo2Max = ""; avgSpo2 = ""; sleepAvgSpo2 = ""; currentSpo2 = ""
                },
                enabled = (spo2Min.isNotEmpty() && spo2Max.isNotEmpty()) || avgSpo2.isNotEmpty() || sleepAvgSpo2.isNotEmpty() || currentSpo2.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save SpO2") }
        }

        // Stress
        EntrySection(title = "🧠 Stress") {
            var stressMin by remember { mutableStateOf("") }
            var stressMax by remember { mutableStateOf("") }
            var avgStress by remember { mutableStateOf("") }
            var currentStress by remember { mutableStateOf("") }
            var customCategory by remember { mutableStateOf("") }

            Text("Daily Range (0-100)", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = stressMin, onValueChange = { stressMin = it.filter { c -> c.isDigit() } },
                    label = { Text("Min") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = stressMax, onValueChange = { stressMax = it.filter { c -> c.isDigit() } },
                    label = { Text("Max") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            val avg = avgStress.toIntOrNull()
            val curr = currentStress.toIntOrNull()
            val refScore = curr ?: avg
            val autoCat = when {
                refScore == null -> ""
                refScore in 1..33 -> "Relaxed"
                refScore in 34..66 -> "Moderate"
                refScore in 67..100 -> "High"
                else -> ""
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = avgStress, onValueChange = { avgStress = it.filter { c -> c.isDigit() } },
                    label = { Text("Average Score") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = currentStress, onValueChange = { currentStress = it.filter { c -> c.isDigit() } },
                    label = { Text("Current Stress") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = customCategory, onValueChange = { customCategory = it },
                label = { Text("Category") }, placeholder = { if (autoCat.isNotEmpty()) Text(autoCat) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val min = stressMin.toIntOrNull()
                    val max = stressMax.toIntOrNull()
                    val range = if (min != null && max != null) MetricRange(min, max) else null
                    val cat = customCategory.ifBlank { autoCat.ifBlank { null } }
                    val data = ParsedHealthData(
                        stress = StressDetail(range = range, average = avg, category = cat, current = curr),
                        stressLevel = curr ?: avg ?: max ?: min,
                        stressCategory = cat
                    )
                    viewModel.syncManualEntry(data)
                    showSuccess = "Stress synced!"
                    stressMin = ""; stressMax = ""; avgStress = ""; currentStress = ""; customCategory = ""
                },
                enabled = (stressMin.isNotEmpty() && stressMax.isNotEmpty()) || avgStress.isNotEmpty() || currentStress.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Stress") }
        }

        // Activity
        EntrySection(title = "🚶 Activity & Stand") {
            var steps by remember { mutableStateOf("") }
            var stepsGoal by remember { mutableStateOf("") }
            var calories by remember { mutableStateOf("") }
            var caloriesGoal by remember { mutableStateOf("") }
            var standHours by remember { mutableStateOf("") }
            var standGoalHours by remember { mutableStateOf("") }
            var distance by remember { mutableStateOf("") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = steps, onValueChange = { steps = it.filter { c -> c.isDigit() } },
                    label = { Text("Steps") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = stepsGoal, onValueChange = { stepsGoal = it.filter { c -> c.isDigit() } },
                    label = { Text("Goal") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = calories, onValueChange = { calories = it.filter { c -> c.isDigit() } },
                    label = { Text("Calories (kcal)") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = caloriesGoal, onValueChange = { caloriesGoal = it.filter { c -> c.isDigit() } },
                    label = { Text("Goal") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = standHours, onValueChange = { standHours = it.filter { c -> c.isDigit() } },
                    label = { Text("Stand Hours") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = standGoalHours, onValueChange = { standGoalHours = it.filter { c -> c.isDigit() } },
                    label = { Text("Goal") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = distance, onValueChange = { distance = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Distance (km)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val s = steps.toLongOrNull()
                    val sg = stepsGoal.toLongOrNull()
                    val c = calories.toIntOrNull()
                    val cg = caloriesGoal.toIntOrNull()
                    val st = standHours.toIntOrNull()
                    val stg = standGoalHours.toIntOrNull()
                    val d = distance.toFloatOrNull()
                    val data = ParsedHealthData(
                        activity = DailyActivity(
                            steps = s,
                            stepsGoal = sg,
                            activeCalories = c,
                            activeCaloriesGoal = cg,
                            standHours = st,
                            standGoalHours = stg,
                            distanceKm = d
                        )
                    )
                    viewModel.syncManualEntry(data)
                    showSuccess = "Activity synced!"
                    steps = ""; stepsGoal = ""; calories = ""; caloriesGoal = ""; standHours = ""; standGoalHours = ""; distance = ""
                },
                enabled = steps.isNotEmpty() || calories.isNotEmpty() || standHours.isNotEmpty() || distance.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Activity") }
        }

        // Weight
        EntrySection(title = "⚖️ Weight") {
            var weight by remember { mutableStateOf("") }
            OutlinedTextField(value = weight, onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Weight (kg)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Spacer(modifier = Modifier.height(8.dp))
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
