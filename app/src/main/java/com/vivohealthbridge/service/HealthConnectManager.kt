package com.vivohealthbridge.service

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import com.vivohealthbridge.data.models.DailyActivity
import com.vivohealthbridge.data.models.ParsedHealthData
import com.vivohealthbridge.data.models.SleepDetail
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "HealthConnectManager"

/**
 * What one [HealthConnectManager.syncAll] pass actually managed to do.
 *
 * Every write is attempted independently, so a single rejected record — a missing
 * permission, a value outside Health Connect's validated range — no longer takes
 * the rest of the night's data down with it.
 */
data class WriteReport(
    val written: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
) {
    val count: Int get() = written.size
    val isSuccess: Boolean get() = failed.isEmpty() && written.isNotEmpty()
    val isPartial: Boolean get() = failed.isNotEmpty() && written.isNotEmpty()
    val isFailure: Boolean get() = written.isEmpty()

    fun summary(): String = when {
        written.isEmpty() && failed.isEmpty() -> "Nothing to write"
        failed.isEmpty() -> "${written.size} record(s) written"
        written.isEmpty() -> "Failed: ${failed.joinToString("; ")}"
        else -> "${written.size} written, ${failed.size} failed"
    }
}

/**
 * Writes what was scraped out of Vivo Health into Health Connect.
 *
 * ## Timestamps
 * Vivo Health reports each sleep vital as a *range over the night* — "51−119 bpm"
 * — never as a series. The values are real; only their position inside the night
 * is unknown. So each range is written as its two endpoints placed a quarter and
 * three quarters of the way through the sleep window, which keeps them inside the
 * night they belong to instead of landing on `Instant.now()` in the middle of the
 * following afternoon.
 *
 * ## Re-syncing the same day
 * Every record carries a `clientRecordId` derived from the metric and the date,
 * with `clientRecordVersion` set to the sync timestamp. Syncing twice in one day
 * therefore *replaces* the earlier records instead of stacking duplicate sleep
 * sessions on top of each other.
 *
 * ## What is deliberately not written
 * Exercise minutes and stand hours have no honest Health Connect equivalent — the
 * only vehicle would be an `ExerciseSessionRecord`, which would mean inventing a
 * workout with start and end times that never happened. They are parsed, shown in
 * the app and kept in the sync log, but not pushed. Same for the stress level:
 * Health Connect has no stress record, and mapping it onto HRV would fabricate a
 * heart-rate-variability reading.
 */
class HealthConnectManager(private val context: Context) {

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
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        } catch (e: Exception) {
            Log.e(TAG, "Error checking HC availability", e)
            false
        }
    }

    fun getPermissions(): Set<String> = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(RestingHeartRateRecord::class),
        HealthPermission.getWritePermission(RespiratoryRateRecord::class),
        HealthPermission.getWritePermission(OxygenSaturationRecord::class),
        HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
    )

    suspend fun hasAllPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        return try {
            client.permissionController.getGrantedPermissions().any { it in getPermissions() }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting granted permissions", e)
            false
        }
    }

    /** Permissions we ask for but have not been granted, for the UI to explain. */
    suspend fun missingPermissions(): Set<String> {
        val client = healthConnectClient ?: return getPermissions()
        return try {
            getPermissions() - client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting granted permissions", e)
            emptySet()
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Sync
    // ══════════════════════════════════════════════════════════════

    suspend fun syncAll(data: ParsedHealthData): WriteReport {
        val client = healthConnectClient
            ?: return WriteReport(failed = listOf("Health Connect is not available"))

        val report = Report()
        val zone = ZoneId.systemDefault()

        data.activity?.let { writeActivity(client, it, data.syncTimestamp, zone, report) }
        data.sleep?.let { writeSleep(client, it, data.syncTimestamp, zone, report) }
        writeLooseMetrics(client, data, zone, report)

        Log.d(TAG, "sync report: ${report.build().summary()}")
        return report.build()
    }

    // ── Activity rings ────────────────────────────────────────────

    private suspend fun writeActivity(
        client: HealthConnectClient,
        activity: DailyActivity,
        version: Long,
        zone: ZoneId,
        report: Report,
    ) {
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant()
        val nowInstant = Instant.now()
        // Guard against a device clock that puts "now" before midnight local.
        val dayEnd = if (nowInstant.isAfter(dayStart)) nowInstant else dayStart.plusSeconds(60)
        val offset = zone.rules.getOffset(dayStart)

        val steps = activity.steps
        if (steps != null && steps > 0) {
            report.attempt("Steps ($steps)") {
                client.insert(
                    StepsRecord(
                        count = steps,
                        startTime = dayStart,
                        startZoneOffset = offset,
                        endTime = dayEnd,
                        endZoneOffset = offset,
                        metadata = meta("steps-$today", version),
                    )
                )
            }
        } else if (steps == 0L) {
            report.skip("Steps (0 — nothing to record)")
        }

        val distance = activity.distanceKm
        if (distance != null && distance > 0f) {
            report.attempt("Distance ($distance km)") {
                client.insert(
                    DistanceRecord(
                        distance = Length.kilometers(distance.toDouble()),
                        startTime = dayStart,
                        startZoneOffset = offset,
                        endTime = dayEnd,
                        endZoneOffset = offset,
                        metadata = meta("distance-$today", version),
                    )
                )
            }
        }

        val calories = activity.activeCalories
        if (calories != null && calories > 0) {
            report.attempt("Active calories ($calories kcal)") {
                client.insert(
                    ActiveCaloriesBurnedRecord(
                        energy = Energy.kilocalories(calories.toDouble()),
                        startTime = dayStart,
                        startZoneOffset = offset,
                        endTime = dayEnd,
                        endZoneOffset = offset,
                        metadata = meta("active-calories-$today", version),
                    )
                )
            }
        }

        // No faithful Health Connect record exists for either of these.
        activity.exerciseMinutes?.let {
            report.skip("Exercise minutes ($it min — no Health Connect record type)")
        }
        activity.standHours?.let {
            report.skip("Stand hours ($it hr — no Health Connect record type)")
        }
    }

    // ── Sleep ─────────────────────────────────────────────────────

    private suspend fun writeSleep(
        client: HealthConnectClient,
        sleep: SleepDetail,
        version: Long,
        zone: ZoneId,
        report: Report,
    ) {
        val window = sleepWindow(sleep, zone)
        if (window == null) {
            report.skip("Sleep (bed and wake times were not read from the chart)")
            return
        }
        val (start, end) = window
        val date = end.atZone(zone).toLocalDate()
        val offset = zone.rules.getOffset(start)
        val endOffset = zone.rules.getOffset(end)

        // ── The session itself, with the stage breakdown ──────────
        val stages = buildStages(sleep, start, end)
        report.attempt("Sleep session (${sleep.totalMinutes ?: "?"} min, ${stages.size} stages)") {
            client.insert(
                SleepSessionRecord(
                    startTime = start,
                    startZoneOffset = offset,
                    endTime = end,
                    endZoneOffset = endOffset,
                    title = buildString {
                        append("Vivo sleep")
                        sleep.score?.let { append(" · $it pts") }
                        sleep.scoreLabel?.let { append(" · $it") }
                    },
                    notes = sleepNotes(sleep),
                    stages = stages,
                    metadata = meta("sleep-$date", version),
                )
            )
        }

        val early = start.fraction(0.25, end)
        val late = start.fraction(0.75, end)
        val middle = start.fraction(0.5, end)

        // ── Heart rate: the two range endpoints, plus resting ─────
        sleep.heartRate?.let { hr ->
            report.attempt("Sleep heart rate (${hr.min}–${hr.max} bpm)") {
                client.insert(
                    HeartRateRecord(
                        startTime = early,
                        startZoneOffset = zone.rules.getOffset(early),
                        endTime = late,
                        endZoneOffset = zone.rules.getOffset(late),
                        samples = listOf(
                            HeartRateRecord.Sample(early, hr.min.toLong()),
                            HeartRateRecord.Sample(late, hr.max.toLong()),
                        ),
                        metadata = meta("sleep-hr-$date", version),
                    )
                )
            }
            // The lowest heart rate of the night is the closest thing the watch
            // gives us to a resting measurement.
            report.attempt("Resting heart rate (${hr.min} bpm)") {
                client.insert(
                    RestingHeartRateRecord(
                        time = middle,
                        zoneOffset = zone.rules.getOffset(middle),
                        beatsPerMinute = hr.min.toLong(),
                        metadata = meta("sleep-resting-hr-$date", version),
                    )
                )
            }
        }

        sleep.respiratoryRate?.let { rr ->
            report.attempt("Sleep respiratory rate (${rr.min}–${rr.max} /min)") {
                client.insert(
                    listOf(
                        RespiratoryRateRecord(
                            time = early,
                            zoneOffset = zone.rules.getOffset(early),
                            rate = rr.min.toDouble(),
                            metadata = meta("sleep-rr-min-$date", version),
                        ),
                        RespiratoryRateRecord(
                            time = late,
                            zoneOffset = zone.rules.getOffset(late),
                            rate = rr.max.toDouble(),
                            metadata = meta("sleep-rr-max-$date", version),
                        ),
                    )
                )
            }
        }

        sleep.spo2?.let { spo2 ->
            report.attempt("Sleep SpO2 (${spo2.min}–${spo2.max} %)") {
                client.insert(
                    listOf(
                        oxygen(spo2.min, early, zone, "sleep-spo2-min-$date", version),
                        oxygen(spo2.max, late, zone, "sleep-spo2-max-$date", version),
                    )
                )
            }
        }
        sleep.averageSpo2?.let { avg ->
            report.attempt("Average sleep SpO2 ($avg %)") {
                client.insert(oxygen(avg, middle, zone, "sleep-spo2-avg-$date", version))
            }
        }

        sleep.hrv?.let { hrv ->
            report.attempt("Sleep HRV (${hrv.min}–${hrv.max} ms)") {
                client.insert(
                    listOf(
                        rmssd(hrv.min.toDouble(), early, zone, "sleep-hrv-min-$date", version),
                        rmssd(hrv.max.toDouble(), late, zone, "sleep-hrv-max-$date", version),
                    )
                )
            }
        }

        sleep.score?.let { report.skip("Sleep score ($it pts — kept in the app's log only)") }
        sleep.continuityScore?.let {
            report.skip("Deep sleep continuity ($it pts — kept in the app's log only)")
        }
    }

    /**
     * Vivo Health only reports stage *totals*, never when each stage occurred, so
     * they are laid down back to back from the start of the session: deep, light,
     * REM, then the awake time. Each block is clipped at the end of the session so
     * a rounded-up total can never produce a stage that runs past the record.
     */
    private fun buildStages(
        sleep: SleepDetail,
        start: Instant,
        end: Instant,
    ): List<SleepSessionRecord.Stage> {
        val stages = mutableListOf<SleepSessionRecord.Stage>()
        var cursor = start

        fun add(minutes: Int?, type: Int) {
            val value = minutes ?: return
            if (value <= 0 || !cursor.isBefore(end)) return
            val stageEnd = minOf(cursor.plusSeconds(value * 60L), end)
            if (stageEnd.isAfter(cursor)) {
                stages.add(SleepSessionRecord.Stage(cursor, stageEnd, type))
                cursor = stageEnd
            }
        }

        add(sleep.deepMinutes, SleepSessionRecord.STAGE_TYPE_DEEP)
        add(sleep.lightMinutes, SleepSessionRecord.STAGE_TYPE_LIGHT)
        add(sleep.remMinutes, SleepSessionRecord.STAGE_TYPE_REM)
        add(sleep.awakeMinutes, SleepSessionRecord.STAGE_TYPE_AWAKE)
        return stages
    }

    private fun sleepNotes(sleep: SleepDetail): String? {
        val parts = buildList {
            sleep.deepPercent?.let { add("Deep $it%${sleep.deepRating?.let { r -> " ($r)" } ?: ""}") }
            sleep.lightPercent?.let { add("Light $it%${sleep.lightRating?.let { r -> " ($r)" } ?: ""}") }
            sleep.remPercent?.let { add("REM $it%${sleep.remRating?.let { r -> " ($r)" } ?: ""}") }
            sleep.awakenings?.let { add("$it awakening(s)") }
            sleep.continuityScore?.let { add("Continuity $it pts") }
            if (sleep.stagesDerived) add("Stage minutes derived from proportions")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /**
     * Turns "22:04" and "08:41" into real instants. A bed time later in the clock
     * than the wake time means the night crossed midnight, so it belongs to the
     * previous day; and if the resulting wake-up would be in the future the whole
     * window is shifted back a day (the screen was showing last night).
     */
    private fun sleepWindow(sleep: SleepDetail, zone: ZoneId): Pair<Instant, Instant>? {
        val bed = minutesOfDay(sleep.bedTime) ?: return null
        val wake = minutesOfDay(sleep.wakeTime) ?: return null

        var wakeDate = LocalDate.now(zone)
        var end = wakeDate.atStartOfDay(zone).plusMinutes(wake.toLong()).toInstant()
        if (end.isAfter(Instant.now())) {
            wakeDate = wakeDate.minusDays(1)
            end = wakeDate.atStartOfDay(zone).plusMinutes(wake.toLong()).toInstant()
        }

        val bedDate = if (bed > wake) wakeDate.minusDays(1) else wakeDate
        val start = bedDate.atStartOfDay(zone).plusMinutes(bed.toLong()).toInstant()

        return if (end.isAfter(start)) start to end else null
    }

    private fun minutesOfDay(clock: String?): Int? {
        val parts = clock?.split(":") ?: return null
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = parts[1].trim().toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return hour * 60 + minute
    }

    private fun Instant.fraction(of: Double, until: Instant): Instant {
        val seconds = Duration.between(this, until).seconds
        return plusSeconds((seconds * of).toLong())
    }

    // ── Loose single metrics (Manual Entry, later phases) ─────────

    private suspend fun writeLooseMetrics(
        client: HealthConnectClient,
        data: ParsedHealthData,
        zone: ZoneId,
        report: Report,
    ) {
        val now = Instant.ofEpochMilli(data.syncTimestamp)
        val offset = zone.rules.getOffset(now)
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant()
        val dayEnd = if (now.isAfter(dayStart)) now else dayStart.plusSeconds(60)

        // ── Heart Rate ──────────────────────────────────────────
        data.heartRate?.range?.let { hrRange ->
            val early = dayStart.fraction(0.33, dayEnd)
            val late = dayStart.fraction(0.67, dayEnd)
            report.attempt("Daily heart rate (${hrRange.min}–${hrRange.max} bpm)") {
                client.insert(
                    HeartRateRecord(
                        startTime = early,
                        startZoneOffset = zone.rules.getOffset(early),
                        endTime = late,
                        endZoneOffset = zone.rules.getOffset(late),
                        samples = listOf(
                            HeartRateRecord.Sample(early, hrRange.min.toLong()),
                            HeartRateRecord.Sample(late, hrRange.max.toLong()),
                        ),
                        metadata = meta("daily-hr-$today", data.syncTimestamp),
                    )
                )
            }
        }

        val restingBpm = data.heartRate?.restingBpm ?: data.restingHeartRateBpm
        restingBpm?.let { bpm ->
            report.attempt("Resting heart rate ($bpm bpm)") {
                client.insert(
                    RestingHeartRateRecord(
                        time = now,
                        zoneOffset = offset,
                        beatsPerMinute = bpm.toLong(),
                        metadata = meta("rhr-$today", data.syncTimestamp),
                    )
                )
            }
        }

        val singleBpm = data.heartRate?.currentBpm ?: data.heartRateBpm
        if (singleBpm != null) {
            report.attempt("Current heart rate ($singleBpm bpm)") {
                client.insert(
                    HeartRateRecord(
                        startTime = now,
                        startZoneOffset = offset,
                        endTime = now.plusSeconds(1),
                        endZoneOffset = offset,
                        samples = listOf(HeartRateRecord.Sample(now, singleBpm.toLong())),
                        metadata = meta("current-hr-$today", data.syncTimestamp),
                    )
                )
            }
        }

        data.heartRate?.walkingBpm?.let { walkBpm ->
            report.skip("Average walking heart rate ($walkBpm bpm — kept in app history)")
        }

        data.heartRate?.sleepingBpm?.let { sleepBpm ->
            report.skip("Normal sleeping heart rate ($sleepBpm bpm — kept in app history)")
        }

        // ── Oxygen Saturation (SpO₂) ─────────────────────────────
        data.oxygenSaturation?.range?.let { spo2Range ->
            val early = dayStart.fraction(0.33, dayEnd)
            val late = dayStart.fraction(0.67, dayEnd)
            report.attempt("Daily SpO2 range (${spo2Range.min}–${spo2Range.max} %)") {
                client.insert(
                    listOf(
                        oxygen(spo2Range.min, early, zone, "daily-spo2-min-$today", data.syncTimestamp),
                        oxygen(spo2Range.max, late, zone, "daily-spo2-max-$today", data.syncTimestamp),
                    )
                )
            }
        }

        data.oxygenSaturation?.average?.let { avg ->
            report.attempt("Average daily SpO2 ($avg %)") {
                client.insert(oxygen(avg, now, zone, "daily-spo2-avg-$today", data.syncTimestamp))
            }
        }

        data.oxygenSaturation?.averageSleep?.let { avgSleep ->
            if (data.sleep?.averageSpo2 == null) {
                report.attempt("Average sleep SpO2 ($avgSleep %)") {
                    client.insert(oxygen(avgSleep, now, zone, "sleep-spo2-avg-$today", data.syncTimestamp))
                }
            }
        }

        data.oxygenSaturation?.current?.let { percent ->
            report.attempt("Current SpO2 ($percent %)") {
                client.insert(oxygen(percent, now, zone, "current-spo2-$today", data.syncTimestamp))
            }
        }

        // ── Stress (unsupported in Health Connect) ────────────────
        data.stress?.let { st ->
            val desc = listOfNotNull(
                st.current?.let { "current $it" },
                st.average?.let { "avg $it" },
                st.range?.let { "range ${it.min}–${it.max}" },
                st.category
            ).joinToString(", ")
            report.skip("Stress ($desc — Health Connect has no stress record type)")
        } ?: run {
            data.stressLevel?.let {
                report.skip("Stress ($it — Health Connect has no stress record type)")
            }
        }

        // ── Weight ───────────────────────────────────────────────
        data.weightKg?.let { kg ->
            report.attempt("Weight ($kg kg)") {
                client.insert(
                    WeightRecord(
                        time = now,
                        zoneOffset = offset,
                        weight = Mass.kilograms(kg.toDouble()),
                        metadata = meta("weight-$today", data.syncTimestamp),
                    )
                )
            }
        }
    }

    // ── Small builders ────────────────────────────────────────────

    private fun oxygen(
        percent: Int,
        time: Instant,
        zone: ZoneId,
        key: String,
        version: Long,
    ) = OxygenSaturationRecord(
        time = time,
        zoneOffset = zone.rules.getOffset(time),
        percentage = Percentage(percent.coerceIn(0, 100).toDouble()),
        metadata = meta(key, version),
    )

    private fun rmssd(
        millis: Double,
        time: Instant,
        zone: ZoneId,
        key: String,
        version: Long,
    ) = HeartRateVariabilityRmssdRecord(
        time = time,
        zoneOffset = zone.rules.getOffset(time),
        heartRateVariabilityMillis = millis,
        metadata = meta(key, version),
    )

    /**
     * A stable identity per metric per day, so a second sync on the same day
     * updates the record instead of inserting a duplicate. The version is the
     * sync timestamp, which makes the newest write always the winner.
     */
    private fun meta(key: String, version: Long) = Metadata(
        clientRecordId = "vivohealthbridge:$key",
        clientRecordVersion = version,
    )

    private suspend fun HealthConnectClient.insert(record: Record) = insertRecords(listOf(record))

    private suspend fun HealthConnectClient.insert(records: List<Record>) = insertRecords(records)

    // ── Per-write isolation ───────────────────────────────────────

    private class Report {
        val written = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        fun skip(label: String) {
            skipped.add(label)
            Log.d(TAG, "skipped $label")
        }

        fun build() = WriteReport(written.toList(), failed.toList(), skipped.toList())

        suspend fun attempt(label: String, block: suspend () -> Unit) {
            try {
                block()
                written.add(label)
                Log.d(TAG, "wrote $label")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                val reason = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                failed.add("$label → $reason")
                Log.e(TAG, "failed to write $label", t)
            }
        }
    }
}
