package com.example.photocategorycamera.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val name = "migration-test.db"

    @Test
    fun migrate1To2PreservesCategoriesAndCreatesCaptureQueue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, `sortOrder` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")
            execSQL("INSERT INTO categories VALUES ('one', '测试', 0, 1, 123)")
            execSQL("PRAGMA user_version = 1")
            close()
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
            ).build()
        try {
            val cursor = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM categories WHERE id = 'one'")
            cursor.use { assertEquals(true, it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            val queue = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM capture_tasks")
            queue.use { assertEquals(true, it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        } finally { database.close() }
    }

    @Test
    fun migrate3To4PreservesCategoriesAndCaptureQueueAndRemovesPresetTable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, `sortOrder` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")
            execSQL("CREATE TABLE IF NOT EXISTS `capture_tasks` (`id` TEXT NOT NULL, `tempPath` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `categoryName` TEXT NOT NULL, `rootTreeUri` TEXT NOT NULL, `mediaKind` TEXT NOT NULL, `stage` TEXT NOT NULL, `targetUri` TEXT, `error` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            execSQL("CREATE INDEX IF NOT EXISTS `index_capture_tasks_stage` ON `capture_tasks` (`stage`)")
            execSQL("CREATE INDEX IF NOT EXISTS `index_capture_tasks_createdAt` ON `capture_tasks` (`createdAt`)")
            execSQL("CREATE TABLE IF NOT EXISTS `custom_presets` (`id` TEXT NOT NULL, `name` TEXT COLLATE NOCASE NOT NULL, `sortOrder` INTEGER NOT NULL, `recipeVersion` INTEGER NOT NULL, `recipeJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_custom_presets_name` ON `custom_presets` (`name`)")
            execSQL("INSERT INTO categories VALUES ('one', '测试', 0, 1, 123)")
            execSQL("INSERT INTO capture_tasks VALUES ('task', '/tmp/a.jpg', 'one', '测试', 'content://root', 'PHOTO', 'PENDING_SAVE', NULL, NULL, 1, 1)")
            execSQL("INSERT INTO custom_presets VALUES ('preset', '旧预设', 0, 1, '{}', 1, 1)")
            execSQL("PRAGMA user_version = 3")
            close()
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
            ).build()
        try {
            val categories = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM categories WHERE id = 'one'")
            categories.use { assertEquals(true, it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            val tasks = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM capture_tasks WHERE id = 'task'")
            tasks.use { assertEquals(true, it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            val presetTable = database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'custom_presets'",
            )
            presetTable.use { assertEquals(true, it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        } finally { database.close() }
    }
}
