package com.eds.overlay.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for EDS speed-camera points.
 *
 * Schema versioning:
 *  - v1 → v2: (historical) destructive migration
 *  - v2 → v3: Added `description` TEXT column to eds_points
 */
@Database(entities = [EdsPoint::class], version = 3, exportSchema = true)
abstract class EdsDatabase : RoomDatabase() {

    abstract fun edsDao(): EdsDao

    companion object {
        @Volatile
        private var INSTANCE: EdsDatabase? = null

        /** Migration v2 → v3: add description column */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE eds_points ADD COLUMN description TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getInstance(context: Context): EdsDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EdsDatabase::class.java,
                    "eds_database"
                )
                    .addMigrations(MIGRATION_2_3)
                    // Scoped fallback: only apply destructive migration when
                    // upgrading from version 1.
                    .fallbackToDestructiveMigrationFrom(1)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
