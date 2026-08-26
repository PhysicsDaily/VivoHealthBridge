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
import com.vivohealthbridge.service.HealthConnectManager
import com.vivohealthbridge.service.SyncProgress
import com.vivohealthbridge.service.VivoHealthAccessibilityService
import com.vivohealthbridge.service.WriteReport
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
    /** Metrics that were read but deliberately not pushed to Health Connect. */
    val notWritten: List<String> = emptyList(),
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
    private val healthConnectManager: HealthConnectManager = app.healthConnectManager
    private val syncRepository = app.syncRepository

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Live step-by-step progress of the on-screen automation. */
    val syncProgress: StateFlow<SyncProgress> = VivoHealthAccessibilityService.progress

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
        checkStatus()
    }

    // ══════════════════════════════════════════════════════════════
    //  Automated sync
    // ══════════════════════════════════════════════════════════════

    fun startAutoSync(context: Context) {
        if (_uiState.value.isSyncing || VivoHealthAccessibilityService.isSyncing) return

        // Without the service there is nothing to drive the Vivo app, and
        // launching it would just dump the user into Vivo Health with no way back.
        if (!VivoHealthAccessibilityService.isServiceRunning()) {
            _uiState.value = _uiState.value.copy(
                isAccessibilityEnabled = false,
                error = "Turn on VivoHealthBridge in Settings → Accessibility first."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isSyncing = true,
            syncResult = null,
            notWritten = emptyList(),
            error = null
        )

        VivoHealthAccessibilityService.syncCallback = { parsedData ->
            viewModelScope.launch { handleSyncResult(parsedData) }
        }

        if (!VivoHealthAccessibilityService.startSync()) {
            VivoHealthAccessibilityService.syncCallback = null
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                error = "Could not start the accessibility service run."
            )
            return
        }

        if (!launchVivoHealth(context)) {
            // Drop the callback first so the abort does not report an empty sync.
            VivoHealthAccessibilityService.syncCallback = null
            VivoHealthAccessibilityService.stopSync()
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                error = "Could not find the Vivo Health app. Is it installed?"
            )
        }
    }

    fun cancelSync() {
        VivoHealthAccessibilityService.syncCallback = null
        VivoHealthAccessibilityService.stopSync()
        _uiState.value = _uiState.value.copy(isSyncing = false, syncResult = "Sync cancelled")
    }

    fun syncManualEntry(data: ParsedHealthData) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSyncing = true, syncResult = null, notWritten = emptyList(), error = null
            )
            handleSyncResult(data, automated = false)
        }
    }

    private suspend fun handleSyncResult(data: ParsedHealthData, automated: Boolean = true) {
        if (!data.hasAnyData()) {
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                error = if (automated) {
                    "No data was read from Vivo Health. Open the app manually and check " +
                            "the sleep screen loads, then try again."
                } else {
                    "Nothing to sync — every field was empty."
                }
            )
            return
        }

        val report = try {
            healthConnectManager.syncAll(data)
        } catch (e: Exception) {
            Log.e(TAG, "syncAll threw", e)
            WriteReport(failed = listOf(e.message ?: e.javaClass.simpleName))
        }

        val status = when {
            report.isSuccess -> SyncStatus.SUCCESS
            report.isPartial -> SyncStatus.PARTIAL
            else -> SyncStatus.FAILED
        }
        logSyncRecords(data, status, report)

        val prefix = if (automated) "Auto-synced" else "Synced"
        _uiState.value = when {
            report.isFailure -> _uiState.value.copy(
                isSyncing = false,
                lastSyncedData = data,
                notWritten = report.skipped,
                error = "Sync failed — ${report.failed.joinToString("; ")}"
            )
            else -> _uiState.value.copy(
                isSyncing = false,
                lastSyncedData = data,
                lastSyncTime = System.currentTimeMillis(),
                notWritten = report.skipped,
                syncResult = buildString {
                    append("$prefix ${report.count} record(s) to Health Connect")
                    if (report.failed.isNotEmpty()) {
                        append(" · ${report.failed.size} failed: ")
                        append(report.failed.joinToString("; "))
                    }
                },
                error = null
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Sync log
    // ══════════════════════════════════════════════════════════════

    private suspend fun logSyncRecords(
        data: ParsedHealthData,
        status: SyncStatus,
        report: WriteReport,
    ) {
        val ok = status.name
        // Metrics we can read but never push get logged as PARTIAL, so the history
        // shows they were captured without claiming they reached Health Connect.
        val local = SyncStatus.PARTIAL.name

        data.activity?.let { activity ->
            activity.steps?.let {
                val goal = activity.stepsGoal?.let { g -> " / $g" } ?: ""
                syncRepository.logSync(HealthMetricType.STEPS.name, "$it$goal steps", ok)
            }
            activity.distanceKm?.let {
                syncRepository.logSync(HealthMetricType.DISTANCE.name, "$it km", ok)
            }
            activity.activeCalories?.let {
                val goal = activity.activeCaloriesGoal?.let { g -> " / $g" } ?: ""
                syncRepository.logSync(HealthMetricType.ACTIVE_CALORIES.name, "$it$goal kcal", ok)
            }
            activity.exerciseMinutes?.let {
                val goal = activity.exerciseGoalMinutes?.let { g -> " / $g" } ?: ""
                syncRepository.logSync(HealthMetricType.EXERCISE.name, "$it$goal min", local)
            }
            activity.standHours?.let {
                val goal = activity.standGoalHours?.let { g -> " / $g" } ?: ""
                syncRepository.logSync(HealthMetricType.STAND.name, "$it$goal hr", local)
            }
        }

        data.sleep?.let { sleep ->
            val parts = mutableListOf<String>()
            sleep.totalMinutes?.let { parts.add("${it / 60}h ${it % 60}m") }
            if (sleep.bedTime != null && sleep.wakeTime != null) {
                parts.add("${sleep.bedTime}–${sleep.wakeTime}")
            }
            sleep.score?.let { parts.add("score $it") }
            sleep.deepMinutes?.let { parts.add("deep ${it}m") }
            sleep.lightMinutes?.let { parts.add("light ${it}m") }
            sleep.remMinutes?.let { parts.add("rem ${it}m") }
            sleep.awakenings?.let { parts.add("awake ${it}×") }
            if (parts.isNotEmpty()) {
                syncRepository.logSync(HealthMetricType.SLEEP.name, parts.joinToString(" · "), ok)
            }

            sleep.heartRate?.let {
                syncRepository.logSync(HealthMetricType.HEART_RATE.name, "sleep ${it.format("bpm")}", ok)
            }
            sleep.respiratoryRate?.let {
                syncRepository.logSync(
                    HealthMetricType.RESPIRATORY_RATE.name, "sleep ${it.format("/min")}", ok
                )
            }
            sleep.spo2?.let {
                syncRepository.logSync(HealthMetricType.SPO2.name, "sleep ${it.format("%")}", ok)
            }
            sleep.averageSpo2?.let {
                syncRepository.logSync(HealthMetricType.SPO2.name, "sleep avg $it%", ok)
            }
            sleep.hrv?.let {
                syncRepository.logSync(HealthMetricType.HRV.name, "sleep ${it.format("ms")}", ok)
            }
        }

        data.heartRateBpm?.let {
            syncRepository.logSync(HealthMetricType.HEART_RATE.name, "$it bpm", ok)
        }
        data.restingHeartRateBpm?.let {
            syncRepository.logSync(HealthMetricType.HEART_RATE.name, "resting $it bpm", ok)
        }
        data.oxygenSaturation?.let {
            syncRepository.logSync(HealthMetricType.SPO2.name, "$it%", ok)
        }
        data.stressLevel?.let {
            val category = data.stressCategory?.let { c -> " $c" } ?: ""
            syncRepository.logSync(HealthMetricType.STRESS.name, "$it$category", local)
        }
        data.weightKg?.let {
            syncRepository.logSync(HealthMetricType.WEIGHT.name, "$it kg", ok)
        }

        Log.d(TAG, "logged sync (${report.summary()})")
    }

    fun clearSyncResult() {
        _uiState.value = _uiState.value.copy(syncResult = null, error = null)
    }

    fun clearHistory() {
        viewModelScope.launch {
            syncRepository.deleteOldRecords(System.currentTimeMillis() + 1)
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Environment checks
    // ══════════════════════════════════════════════════════════════

    private fun launchVivoHealth(context: Context): Boolean {
        for (pkg in VivoHealthAccessibilityService.VIVO_HEALTH_PACKAGES) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d(TAG, "Launched Vivo Health: $pkg")
                return true
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
            if (ComponentName.unflattenFromString(colonSplitter.next()) == expectedName) return true
        }
        return false
    }
}
