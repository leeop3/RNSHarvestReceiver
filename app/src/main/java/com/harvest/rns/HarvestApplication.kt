package com.harvest.rns

import android.app.Application
import android.util.Log
import com.harvest.rns.data.db.HarvestDatabase

/**
 * Application class — initialises the Room database eagerly on startup
 * so the first DB access on the main thread is avoided.
 */
class HarvestApplication : Application() {

    companion object {
        private const val TAG = "HarvestApplication"
        lateinit var instance: HarvestApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "RNS Harvest Receiver starting up")

        // Warm up the database connection on a background thread
        Thread {
            HarvestDatabase.getInstance(applicationContext)
            Log.d(TAG, "Database initialised")
        }.apply {
            name = "db-init"
            isDaemon = true
            start()
        }
    }
}
