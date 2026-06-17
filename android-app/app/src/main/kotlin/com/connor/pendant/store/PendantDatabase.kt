package com.connor.pendant.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AudioSegment::class],
    version = 1,
    exportSchema = false,
)
abstract class PendantDatabase : RoomDatabase() {
    abstract fun audioSegments(): AudioSegmentDao

    companion object {
        @Volatile private var INSTANCE: PendantDatabase? = null

        fun get(context: Context): PendantDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PendantDatabase::class.java,
                    "pendant.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
