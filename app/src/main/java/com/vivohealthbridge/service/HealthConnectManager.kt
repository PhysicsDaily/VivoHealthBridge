package com.vivohealthbridge.service

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import com.vivohealthbridge.data.models.ParsedHealthData
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class HealthConnectManager(private val context: Context) {

    companion object {
        private const val TAG = "HealthConnectManager"
    }

    private val healthConnectClient: HealthConnectClient? by lazy {
        try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create HealthConnectClient", e)
            null
        }
    }

    fun checkAvailability(): Boolean {
        return try {
            val status = HealthConnectClient.getSdkStatus(context)
            status == HealthConnectClient.SDK_AVAILABLE
        } catch (e: Exception) {
            Log.e(TAG, "Error checking HC availability", e)
            false
        }
    }

    fun getPermissions(): Set<String> {
        return setOf(
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(RestingHeartRateRecord::class),
            HealthPermission.getWritePermission(SleepSessionRecord::class),
            HealthPermission.getWritePermission(OxygenSaturationRecord::class),
            HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getWritePermission(WeightRecord::class),
        )
    }

    suspend fun hasAllPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            granted.any { it in getPermissions() }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting granted permissions", e)
            false
        }
    }

    suspend fun syncAll(data: ParsedHealthData): Result<Int> {
        var count = 0
        val errors = mutableListOf<String>()

        try {
            if (data.sleepTotalMinutes != null && data.sleepStartTime != null && data.sleepEndTime != null) {
                writeSleepSession(data)
                count++
            }
            if (data.heartRateBpm != null) {
                writeHeartRate(data.heartRateBpm, Instant.now())
                count++
            }
            if (data.restingHeartRateBpm != null) {
                writeRestingHeartRate(data.restingHeartRateBpm, Instant.now())
                count++
            }
            if (data.oxygenSaturation != null) {
                writeOxygenSaturation(data.oxygenSaturation, Instant.now())
                count++
            }
            if (data.stressLevel != null) {
                writeStress(data.stressLevel, Instant.now())
                count++
            }
            if (data.weightKg != null) {
                writeWeight(data.weightKg, Instant.now())
                count++
            }
            if (data.steps != null) {
                val now = Instant.now()
                val startOfDay = LocalDate.now()
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                writeSteps(data.steps, startOfDay, now)
                count++
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during syncAll", e)
            errors.add(e.message ?: "Unknown error")
        }

        return if (errors.isEmpty()) {
            Result.success(count)
        } else {
            if (count > 0) Result.success(count) else Result.failure(Exception(errors.joinToString("; ")))
        }
    }

    suspend fun writeSleepSession(data: ParsedHealthData) {
        val client = healthConnectClient ?: throw Exception("Health Connect not available")

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()

        val startTimeParts = data.sleepStartTime!!.split(":")
        val endTimeParts = data.sleepEndTime!!.split(":")

        val startHour = startTimeParts[0].toInt()
        val startMin = startTimeParts[1].toInt()
        val endHour = endTimeParts[0].toInt()
        val endMin = endTimeParts[1].toInt()

        // Sleep start is usually the previous evening
        val sleepStartDate = if (startHour >= 18) today.minusDays(1) else today
        val sleepStartInstant = ZonedDateTime.of(
            sleepStartDate, LocalTime.of(startHour, startMin), zone
        ).toInstant()
        val sleepEndInstant = ZonedDateTime.of(
            today, LocalTime.of(endHour, endMin), zone
        ).toInstant()

        val zoneOffset = zone.rules.getOffset(sleepStartInstant)

        // Build sleep stages distributed sequentially within the sleep window
        val stages = mutableListOf<SleepSessionRecord.Stage>()
        var cursor = sleepStartInstant

        if (data.deepSleepMinutes != null && data.deepSleepMinutes > 0) {
            val end = cursor.plusSeconds(data.deepSleepMinutes.toLong() * 60)
            stages.add(SleepSessionRecord.Stage(cursor, end, SleepSessionRecord.STAGE_TYPE_DEEP))
            cursor = end
        }
        if (data.lightSleepMinutes != null && data.lightSleepMinutes > 0) {
            val end = cursor.plusSeconds(data.lightSleepMinutes.toLong() * 60)
            stages.add(SleepSessionRecord.Stage(cursor, end, SleepSessionRecord.STAGE_TYPE_LIGHT))
            cursor = end
        }
        if (data.remSleepMinutes != null && data.remSleepMinutes > 0) {
            val end = cursor.plusSeconds(data.remSleepMinutes.toLong() * 60)
            stages.add(SleepSessionRecord.Stage(cursor, end, SleepSessionRecord.STAGE_TYPE_REM))
            cursor = end
        }
        if (data.awakeMinutes != null && data.awakeMinutes > 0) {
            val end = cursor.plusSeconds(data.awakeMinutes.toLong() * 60)
            stages.add(SleepSessionRecord.Stage(cursor, end, SleepSessionRecord.STAGE_TYPE_AWAKE))
            cursor = end
        }

        val record = SleepSessionRecord(
            startTime = sleepStartInstant,
            startZoneOffset = zoneOffset,
            endTime = sleepEndInstant,
            endZoneOffset = zoneOffset,
            title = "Vivo Watch Sleep",
            stages = stages,
        )

        client.insertRecords(listOf(record))
        Log.d(TAG, "Sleep session written: ${data.sleepTotalMinutes}min with ${stages.size} stages")
    }

    suspend fun writeHeartRate(bpm: Int, timestamp: Instant) {
        val client = healthConnectClient ?: throw Exception("Health Connect not available")
        val offset = ZoneId.systemDefault().rules.getOffset(timestamp)

        val record = HeartRateRecord(
            startTime = timestamp,
            startZoneOffset = offset,
            endTime = timestamp.plusSeconds(1),
            endZoneOffset = offset,
            samples = listOf(HeartRateRecord.Sample(timestamp, bpm.toLong())),
        )
        client.insertRecords(listOf(record))
        Log.d(TAG, "Heart rate written: $bpm bpm")
    }

    suspend fun writeRestingHeartRate(bpm: Int, timestamp: Instant) {
        val client = healthConnectClient ?: throw Exception("Health Connect not available")
        val offset = ZoneId.systemDefault().rules.getOffset(timestamp)

        val record = RestingHeartRateRecord(
            time = timestamp,
            zoneOffset = offset,
            beatsPerMinute = bpm.toLong(),
        )
        client.insertRecords(listOf(record))
        Log.d(TAG, "Resting heart rate written: $bpm bpm")
    }

    suspend fun writeOxygenSaturation(percentage: Int, timestamp: Instant) {
        val client = healthConnectClient ?: throw Exception("Health Connect not available")
        val offset = ZoneId.systemDefault().rules.getOffset(timestamp)

        val record = OxygenSaturationRecord(
            time = timestamp,
            zoneOffset = offset,
            percentage = Percentage(percentage.toDouble()),
        )
        client.insertRecords(listOf(record))
        Log.d(TAG, "SpO2 written: $percentage%")
    }

    suspend fun writeStress(level: Int, timestamp: Instant) {
        val client = healthConnectClient ?: throw Exception("Health Connect not available")
        val offset = ZoneId.systemDefault().rules.getOffset(timestamp)

        // Map stress (0-100) inversely to HRV RMSSD (ms)
        // Higher stress = lower HRV. Typical RMSSD: 20-100ms
        val hrv = (100.0 - level * 0.8).coerceIn(15.0, 120.0)

        val record = HeartRateVariabilityRmssdRecord(
            time = timestamp,
            zoneOffset = offset,
            heartRateVariabilityMillis = hrv,
        )
        client.insertRecords(listOf(record))
        Log.d(TAG, "Stress written as HRV: level=$level -> HRV=${hrv}ms")
    }

    suspend fun writeWeight(kg: Float, timestamp: Instant) {
        val client = healthConnectClient ?: throw Exception("Health Connect not available")
        val offset = ZoneId.systemDefault().rules.getOffset(timestamp)

        val record = WeightRecord(
            time = timestamp,
            zoneOffset = offset,
            weight = Mass.kilograms(kg.toDouble()),
        )
        client.insertRecords(listOf(record))
        Log.d(TAG, "Weight written: $kg kg")
    }

    suspend fun writeSteps(count: Long, startTime: Instant, endTime: Instant) {
        val client = healthConnectClient ?: throw Exception("Health Connect not available")
        val offset = ZoneId.systemDefault().rules.getOffset(startTime)

        val record = StepsRecord(
            count = count,
            startTime = startTime,
            startZoneOffset = offset,
            endTime = endTime,
            endZoneOffset = offset,
        )
        client.insertRecords(listOf(record))
        Log.d(TAG, "Steps written: $count")
    }
}
