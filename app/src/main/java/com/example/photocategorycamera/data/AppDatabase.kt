package com.example.photocategorycamera.data

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CategoryEntity::class, CategoryGroupEntity::class, CaptureTaskEntity::class, DocumentDraftEntity::class, DirectoryOperationEntity::class],
    version = 8,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun categoryGroupDao(): CategoryGroupDao
    abstract fun directoryOperationDao(): DirectoryOperationDao
    abstract fun captureTaskDao(): CaptureTaskDao
    abstract fun documentDraftDao(): DocumentDraftDao

    companion object {
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE directory_operations ADD COLUMN targetBeforeCategoryId TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // SQL only records the old location. The journaled worker moves media after opening.
                database.execSQL("ALTER TABLE categories ADD COLUMN homeAtRoot INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE categories SET homeAtRoot = 1 WHERE groupId = 'home'")
                database.execSQL("UPDATE category_groups SET name = '常用' WHERE id = 'home' AND NOT EXISTS (SELECT 1 FROM category_groups WHERE id != 'home' AND name = '常用' COLLATE NOCASE)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS category_groups (
                        id TEXT NOT NULL PRIMARY KEY, name TEXT COLLATE NOCASE NOT NULL,
                        sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL
                    )""",
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_category_groups_name ON category_groups (name)")
                database.execSQL("INSERT OR IGNORE INTO category_groups (id, name, sortOrder, createdAt) VALUES ('home', '常用', -1, 0)")
                database.execSQL("ALTER TABLE categories ADD COLUMN groupId TEXT NOT NULL DEFAULT 'home'")
                database.execSQL("DROP INDEX IF EXISTS index_categories_name")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_groupId_name ON categories (groupId, name)")
                for (table in listOf("capture_tasks", "document_drafts")) {
                    database.execSQL("ALTER TABLE $table ADD COLUMN groupId TEXT NOT NULL DEFAULT 'home'")
                    database.execSQL("ALTER TABLE $table ADD COLUMN groupName TEXT DEFAULT NULL")
                }
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS directory_operations (
                        id TEXT NOT NULL, kind TEXT NOT NULL, categoryId TEXT,
                        sourceGroupId TEXT NOT NULL, targetGroupId TEXT NOT NULL, rootTreeUri TEXT NOT NULL,
                        sourceGroupName TEXT, targetGroupName TEXT, sourceName TEXT NOT NULL, targetName TEXT NOT NULL,
                        stateJson TEXT NOT NULL, stage TEXT NOT NULL, error TEXT,
                        createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, slot INTEGER, PRIMARY KEY(id)
                    )""",
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_directory_operations_slot ON directory_operations (slot)")
            }
        }

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
