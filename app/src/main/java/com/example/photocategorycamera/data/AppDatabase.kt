package com.example.photocategorycamera.data

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CategoryEntity::class, CaptureTaskEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun captureTaskDao(): CaptureTaskDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `capture_tasks` (
                        |`id` TEXT NOT NULL,
                        |`tempPath` TEXT NOT NULL,
                        |`categoryId` TEXT NOT NULL,
                        |`categoryName` TEXT NOT NULL,
                        |`rootTreeUri` TEXT NOT NULL,
                        |`mediaKind` TEXT NOT NULL,
                        |`stage` TEXT NOT NULL,
                        |`targetUri` TEXT,
                        |`error` TEXT,
                        |`createdAt` INTEGER NOT NULL,
                        |`updatedAt` INTEGER NOT NULL,
                        |PRIMARY KEY(`id`))
                    """.trimMargin(),
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_capture_tasks_stage` ON `capture_tasks` (`stage`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_capture_tasks_createdAt` ON `capture_tasks` (`createdAt`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `custom_presets` (
                        |`id` TEXT NOT NULL,
                        |`name` TEXT COLLATE NOCASE NOT NULL,
                        |`sortOrder` INTEGER NOT NULL,
                        |`recipeVersion` INTEGER NOT NULL,
                        |`recipeJson` TEXT NOT NULL,
                        |`createdAt` INTEGER NOT NULL,
                        |`updatedAt` INTEGER NOT NULL,
                        |PRIMARY KEY(`id`))
                    """.trimMargin(),
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_custom_presets_name` ON `custom_presets` (`name`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS `custom_presets`")
            }
        }
    }
}
