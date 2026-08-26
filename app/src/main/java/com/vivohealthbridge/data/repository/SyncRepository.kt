package com.vivohealthbridge.data.repository

import com.vivohealthbridge.data.db.SyncDao
import com.vivohealthbridge.data.db.SyncRecord
import kotlinx.coroutines.flow.Flow

class SyncRepository(private val dao: SyncDao) {

    fun getAllRecords(): Flow<List<SyncRecord>> = dao.getAllRecords()

    fun getRecordsByType(type: String): Flow<List<SyncRecord>> = dao.getRecordsByType(type)

    fun getRecordsByDate(startDate: Long, endDate: Long): Flow<List<SyncRecord>> =
        dao.getRecordsByDate(startDate, endDate)

    suspend fun insertRecord(record: SyncRecord): Long = dao.insertRecord(record)

    suspend fun getLastSyncTime(): Long? = dao.getLastSyncTime()

    suspend fun deleteOldRecords(olderThanTimestamp: Long) = dao.deleteOldRecords(olderThanTimestamp)

    suspend fun logSync(metricType: String, value: String, status: String): Long {
        return dao.insertRecord(
            SyncRecord(
                metricType = metricType,
                value = value,
                syncedAt = System.currentTimeMillis(),
                status = status
            )
        )
    }
}
