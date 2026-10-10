package com.example.photocategorycamera.data

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CategoryEntity::class, CaptureTaskEntity::class, DocumentDraftEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun captureTaskDao(): CaptureTaskDao
    abstract fun documentDraftDao(): DocumentDraftDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("UPDATE capture_tasks SET mediaKind = 'PHOTO' WHERE mediaKind = 'MOTION_PHOTO'")
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS document_drafts (
                        id TEXT NOT NULL PRIMARY KEY, sourcePath TEXT NOT NULL,
                        categoryId TEXT NOT NULL, categoryName TEXT NOT NULL, rootTreeUri TEXT NOT NULL,
                        quadValue TEXT NOT NULL, effectValue TEXT NOT NULL, quarterTurns INTEGER NOT NULL,
                        stage TEXT NOT NULL, editingSlot INTEGER, replacementPath TEXT, createdAt INTEGER NOT NULL
                    )""",
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_document_drafts_editingSlot ON document_drafts (editingSlot)")
            }
        }

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
