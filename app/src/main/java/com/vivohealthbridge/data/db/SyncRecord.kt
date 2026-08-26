package com.vivohealthbridge.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_records")
data class SyncRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val metricType: String,
    val value: String,
    val syncedAt: Long,
    val status: String,
    val healthConnectRecordId: String? = null
)
