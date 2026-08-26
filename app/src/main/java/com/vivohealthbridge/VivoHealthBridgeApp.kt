package com.vivohealthbridge

import android.app.Application
import androidx.room.Room
import com.vivohealthbridge.data.db.SyncDatabase
import com.vivohealthbridge.data.repository.SyncRepository
import com.vivohealthbridge.service.HealthConnectManager

class VivoHealthBridgeApp : Application() {

    lateinit var database: SyncDatabase
        private set

    lateinit var syncRepository: SyncRepository
        private set

    lateinit var healthConnectManager: HealthConnectManager
        private set

    override fun onCreate() {
        super.onCreate()

        database = Room.databaseBuilder(
            applicationContext,
            SyncDatabase::class.java,
            "vivo_health_bridge_db"
        ).build()

        syncRepository = SyncRepository(database.syncDao())
        healthConnectManager = HealthConnectManager(applicationContext)
    }
}
