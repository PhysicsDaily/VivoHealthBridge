package com.vivohealthbridge.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vivohealthbridge.VivoHealthBridgeApp
import com.vivohealthbridge.data.models.HealthMetricType
import com.vivohealthbridge.data.models.ParsedHealthData
import com.vivohealthbridge.data.models.SyncStatus
import com.vivohealthbridge.data.db.SyncRecord
import com.vivohealthbridge.service.HealthConnectManager
import com.vivohealthbridge.service.VivoHealthAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiState(
    val isSyncing: Boolean = false,
    val lastSyncedData: ParsedHealthData? = null,
    val lastSyncTime: Long? = null,
    val syncResult: String? = null,
    val isAccessibilityEnabled: Boolean = false,
    val isHealthConnectAvailable: Boolean = false,
    val healthConnectPermissionsGranted: Boolean = false,
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val app = application as VivoHealthBridgeApp
    private val healthConnectManager = app.healthConnectManager
    private val syncRepository = app.syncRepository

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val syncHistory = syncRepository.getAllRecords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        checkStatus()
    }

    fun checkStatus() {
        viewModelScope.launch {
            val isHcAvailable = healthConnectManager.checkAvailability()
            val hasPermissions = if (isHcAvailable) healthConnectManager.hasAllPermissions() else false
            val isA11yEnabled = checkAccessibilityEnabled(getApplication())
            val lastSync = syncRepository.getLastSyncTime()

            _uiState.value = _uiState.value.copy(
                isHealthConnectAvailable = isHcAvailable,
                healthConnectPermissionsGranted = hasPermissions,
                isAccessibilityEnabled = isA11yEnabled,
                lastSyncTime = lastSync
            )
        }
    }

    fun getPermissions() = healthConnectManager.getPermissions()

    fun onPermissionsResult(granted: Set<String>) {
        _uiState.value = _uiState.value.copy(
            healthConnectPermissionsGranted = granted.isNotEmpty()
        )
    }

    fun startAutoSync(context: Context) {
        if (_uiState.value.isSyncing) return

        _uiState.value = _uiState.value.copy(
            isSyncing = true,
            syncResult = null,
            error = null
        )

        // Set up callback from accessibility service
        VivoHealthAccessibilityService.syncCallback = { parsedData ->
            viewModelScope.launch {
                handleSyncResult(parsedData)
            }
        }

        // Start sync in the accessibility service
        VivoHealthAccessibilityService.startSync()

        // Launch Vivo Health app
        val launched = launchVivoHealth(context)
        if (!launched) {
            VivoHealthAccessibilityService.stopSync()
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                error = "Could not find Vivo Health app. Is it installed?"
            )
        }
    }

    fun syncManualEntry(data: ParsedHealthData) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, syncResult = null, error = null)
            try {
                val result = healthConnectManager.syncAll(data)
                result.onSuccess { count ->
                    logSyncRecords(data, SyncStatus.SUCCESS)
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        lastSyncedData = data,
                        lastSyncTime = System.currentTimeMillis(),
                        syncResult = "✅ Synced $count record(s) to Health Connect"
                    )
                }.onFailure { e ->
                    logSyncRecords(data, SyncStatus.FAILED)
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        error = "❌ Sync failed: ${e.message}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    error = "❌ Error: ${e.message}"
                )
            }
        }
    }

    private suspend fun handleSyncResult(data: ParsedHealthData) {
        try {
            val result = healthConnectManager.syncAll(data)
            result.onSuccess { count ->
                logSyncRecords(data, SyncStatus.SUCCESS)
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    lastSyncedData = data,
                    lastSyncTime = System.currentTimeMillis(),
                    syncResult = "✅ Auto-synced $count record(s) to Health Connect"
                )
            }.onFailure { e ->
                logSyncRecords(data, SyncStatus.FAILED)
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    error = "❌ Sync failed: ${e.message}"
                )
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                error = "❌ Error: ${e.message}"
            )
        }
    }

    private suspend fun logSyncRecords(data: ParsedHealthData, status: SyncStatus) {
        val statusStr = status.name
        // Sleep is the primary metric
        if (data.sleepTotalMinutes != null) {
            val parts = mutableListOf("${data.sleepTotalMinutes}min")
            data.sleepScore?.let { parts.add("score:$it") }
            data.deepSleepMinutes?.let { parts.add("deep:${it}m") }
            data.lightSleepMinutes?.let { parts.add("light:${it}m") }
            data.remSleepMinutes?.let { parts.add("rem:${it}m") }
            data.numberOfAwakenings?.let { parts.add("awake:$it×") }
            syncRepository.logSync(HealthMetricType.SLEEP.name, parts.joinToString(" "), statusStr)
        }
        if (data.sleepHrvMin != null && data.sleepHrvMax != null) {
            syncRepository.logSync(HealthMetricType.HEART_RATE.name, "Sleep HRV ${data.sleepHrvMin}-${data.sleepHrvMax}ms", statusStr)
        }
        if (data.averageSleepSpo2 != null) {
            syncRepository.logSync(HealthMetricType.SPO2.name, "Sleep SpO2 avg ${data.averageSleepSpo2}%", statusStr)
        }
        // Non-sleep metrics
        if (data.heartRateBpm != null) {
            syncRepository.logSync(HealthMetricType.HEART_RATE.name, "${data.heartRateBpm} bpm", statusStr)
        }
        if (data.oxygenSaturation != null) {
            syncRepository.logSync(HealthMetricType.SPO2.name, "${data.oxygenSaturation}%", statusStr)
        }
        if (data.stressLevel != null) {
            syncRepository.logSync(HealthMetricType.STRESS.name, "${data.stressLevel} ${data.stressCategory ?: ""}", statusStr)
        }
        if (data.steps != null) {
            syncRepository.logSync(HealthMetricType.STEPS.name, "${data.steps} steps", statusStr)
        }
        if (data.weightKg != null) {
            syncRepository.logSync(HealthMetricType.WEIGHT.name, "${data.weightKg} kg", statusStr)
        }
    }

    fun clearSyncResult() {
        _uiState.value = _uiState.value.copy(syncResult = null, error = null)
    }

    fun clearHistory() {
        viewModelScope.launch {
            syncRepository.deleteOldRecords(System.currentTimeMillis() + 1)
        }
    }

    private fun launchVivoHealth(context: Context): Boolean {
        val packageNames = listOf("com.vivo.health", "com.vivo.sports")
        for (pkg in packageNames) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Log.d(TAG, "Launched Vivo Health: $pkg")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch $pkg", e)
            }
        }
        return false
    }

    private fun checkAccessibilityEnabled(context: Context): Boolean {
        val expectedName = ComponentName(context, VivoHealthAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (ComponentName.unflattenFromString(componentName) == expectedName) {
                return true
            }
        }
        return false
    }
}
