package com.vivohealthbridge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Insert
    suspend fun insertRecord(record: SyncRecord): Long

    @Query("SELECT * FROM sync_records ORDER BY syncedAt DESC")
    fun getAllRecords(): Flow<List<SyncRecord>>

    @Query("SELECT * FROM sync_records WHERE metricType = :type ORDER BY syncedAt DESC")
    fun getRecordsByType(type: String): Flow<List<SyncRecord>>

    @Query("SELECT * FROM sync_records WHERE syncedAt >= :startDate AND syncedAt <= :endDate ORDER BY syncedAt DESC")
    fun getRecordsByDate(startDate: Long, endDate: Long): Flow<List<SyncRecord>>

    @Query("DELETE FROM sync_records WHERE syncedAt < :timestamp")
    suspend fun deleteOldRecords(timestamp: Long)

    @Query("SELECT MAX(syncedAt) FROM sync_records")
    suspend fun getLastSyncTime(): Long?
}
