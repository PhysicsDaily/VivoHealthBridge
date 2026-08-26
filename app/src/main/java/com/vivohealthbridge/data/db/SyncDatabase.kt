package com.vivohealthbridge.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [SyncRecord::class], version = 1, exportSchema = false)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao
}
