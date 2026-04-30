package com.eds.overlay.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for EDS speed-camera points.
 *
 * Schema versioning note: version is currently 2.  When schema changes require
 * a bump, define a proper Migration.  `fallbackToDestructiveMigration` is
 * intentionally retained as a last-resort safety net, but it is scoped to
 * known old versions via [fallbackToDestructiveMigrationFrom] to avoid
 * silently wiping data on unanticipated version jumps.
 *
 * TODO: supply a real Migration before shipping v3+.
 */
@Database(entities = [EdsPoint::class], version = 2, exportSchema = true)
abstract class EdsDatabase : RoomDatabase() {

    abstract fun edsDao(): EdsDao

    companion object {
        @Volatile
        private var INSTANCE: EdsDatabase? = null

        fun getInstance(context: Context): EdsDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EdsDatabase::class.java,
                    "eds_database"
                )
                    // Scoped fallback: only apply destructive migration when
                    // upgrading from version 1.  Remove this line once a real
                    // Migration is provided.
                    .fallbackToDestructiveMigrationFrom(1)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
