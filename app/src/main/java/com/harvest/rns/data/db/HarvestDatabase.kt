package com.harvest.rns.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.harvest.rns.data.model.HarvestRecord

@Database(
    entities = [HarvestRecord::class],
    version = 1,
    exportSchema = false
)
abstract class HarvestDatabase : RoomDatabase() {

    abstract fun harvestDao(): HarvestDao

    companion object {
        private const val DATABASE_NAME = "harvest_receiver.db"

        @Volatile
        private var INSTANCE: HarvestDatabase? = null

        fun getInstance(context: Context): HarvestDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): HarvestDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                HarvestDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
