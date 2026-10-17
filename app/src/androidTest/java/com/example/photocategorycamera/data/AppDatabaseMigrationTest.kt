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
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
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
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
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

    @Test fun migrate4To5PreservesTargetsAndConvertsLegacyKind() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).apply {
            execSQL("CREATE TABLE categories (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE, sortOrder INTEGER NOT NULL, isActive INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            execSQL("CREATE UNIQUE INDEX index_categories_name ON categories (name)")
            execSQL("CREATE TABLE capture_tasks (id TEXT NOT NULL PRIMARY KEY, tempPath TEXT NOT NULL, categoryId TEXT NOT NULL, categoryName TEXT NOT NULL, rootTreeUri TEXT NOT NULL, mediaKind TEXT NOT NULL, stage TEXT NOT NULL, targetUri TEXT, error TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            execSQL("CREATE INDEX index_capture_tasks_stage ON capture_tasks (stage)")
            execSQL("CREATE INDEX index_capture_tasks_createdAt ON capture_tasks (createdAt)")
            execSQL("INSERT INTO categories VALUES ('one', '测试', 0, 1, 123)")
            execSQL("INSERT INTO capture_tasks VALUES ('legacy', '/test/pending.jpg', 'one', '测试', 'content://fixture/root', 'MOTION_PHOTO', 'RETRY', 'content://fixture/target', NULL, 1, 1)")
            execSQL("PRAGMA user_version = 4")
            close()
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8).build()
        try {
            database.openHelper.readableDatabase.query("SELECT mediaKind, tempPath, rootTreeUri, targetUri, stage FROM capture_tasks WHERE id = 'legacy'").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("PHOTO", it.getString(0))
                assertEquals("/test/pending.jpg", it.getString(1))
                assertEquals("content://fixture/root", it.getString(2))
                assertEquals("content://fixture/target", it.getString(3))
                assertEquals("RETRY", it.getString(4))
            }
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM document_drafts").use {
                assertEquals(true, it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { database.close() }
    }
    @Test fun migrate5To6PreservesHomeLocationsAndCreatesScopedGroupsAndJournal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).apply {
            execSQL("CREATE TABLE categories (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE, sortOrder INTEGER NOT NULL, isActive INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            execSQL("CREATE UNIQUE INDEX index_categories_name ON categories (name)")
            execSQL("CREATE TABLE capture_tasks (id TEXT NOT NULL PRIMARY KEY, tempPath TEXT NOT NULL, categoryId TEXT NOT NULL, categoryName TEXT NOT NULL, rootTreeUri TEXT NOT NULL, mediaKind TEXT NOT NULL, stage TEXT NOT NULL, targetUri TEXT, error TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            execSQL("CREATE INDEX index_capture_tasks_stage ON capture_tasks (stage)")
            execSQL("CREATE INDEX index_capture_tasks_createdAt ON capture_tasks (createdAt)")
            execSQL("CREATE TABLE document_drafts (id TEXT NOT NULL PRIMARY KEY, sourcePath TEXT NOT NULL, categoryId TEXT NOT NULL, categoryName TEXT NOT NULL, rootTreeUri TEXT NOT NULL, quadValue TEXT NOT NULL, effectValue TEXT NOT NULL, quarterTurns INTEGER NOT NULL, stage TEXT NOT NULL, editingSlot INTEGER, replacementPath TEXT, createdAt INTEGER NOT NULL)")
            execSQL("CREATE UNIQUE INDEX index_document_drafts_editingSlot ON document_drafts (editingSlot)")
            execSQL("INSERT INTO categories VALUES ('one', '票据', 7, 0, 123)")
            execSQL("INSERT INTO capture_tasks VALUES ('task', '/pending.jpg', 'one', '票据', 'content://root', 'PHOTO', 'FAILED', 'content://target', 'retry', 1, 2)")
            execSQL("INSERT INTO document_drafts VALUES ('draft', '/draft.jpg', 'one', '票据', 'content://root', 'quad', 'ORIGINAL', 1, 'EDITING', 1, NULL, 3)")
            execSQL("PRAGMA user_version = 5")
            close()
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8).build()
        try {
            val db = database.openHelper.writableDatabase
            db.query("SELECT name, sortOrder, isActive, createdAt, groupId FROM categories WHERE id='one'").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("票据", it.getString(0)); assertEquals(7, it.getInt(1))
                assertEquals(0, it.getInt(2)); assertEquals(123L, it.getLong(3)); assertEquals("home", it.getString(4))
            }
            db.query("SELECT name FROM category_groups WHERE id='home'").use {
                assertEquals(true, it.moveToFirst()); assertEquals("常用", it.getString(0))
            }
            for (table in listOf("capture_tasks", "document_drafts")) {
                db.query("SELECT groupId, groupName, rootTreeUri FROM $table").use {
                    assertEquals(true, it.moveToFirst()); assertEquals("home", it.getString(0))
                    assertEquals(true, it.isNull(1)); assertEquals("content://root", it.getString(2))
                }
            }
            db.query("SELECT targetUri, stage, error FROM capture_tasks WHERE id='task'").use {
                assertEquals(true, it.moveToFirst()); assertEquals("content://target", it.getString(0))
                assertEquals("FAILED", it.getString(1)); assertEquals("retry", it.getString(2))
            }
            db.execSQL("INSERT INTO category_groups VALUES ('trip', '旅行', 0, 4)")
            db.execSQL("INSERT INTO categories (id,name,sortOrder,isActive,createdAt,groupId) VALUES ('two', '票据', 0, 1, 4, 'trip')")
            db.query("SELECT COUNT(*) FROM categories WHERE name='票据'").use {
                assertEquals(true, it.moveToFirst()); assertEquals(2, it.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM directory_operations").use {
                assertEquals(true, it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { database.close() }
    }

    @Test fun migrate6To7MarksOnlyLegacyHomeWithoutChangingFrozenMediaLocations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(name)
        val initial = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        initial.openHelper.writableDatabase.apply {
            createLegacyDirectoryOperations(this)
            execSQL("DROP TABLE categories")
            execSQL("CREATE TABLE categories (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE, sortOrder INTEGER NOT NULL, isActive INTEGER NOT NULL, createdAt INTEGER NOT NULL, groupId TEXT NOT NULL DEFAULT 'home')")
            execSQL("CREATE UNIQUE INDEX index_categories_groupId_name ON categories (groupId,name)")
            execSQL("INSERT INTO category_groups VALUES ('home','首页',-1,0),('trip','旅行',0,1)")
            execSQL("INSERT INTO categories VALUES ('home-cat','笔记',7,0,123,'home'),('custom-cat','笔记',3,1,456,'trip')")
            execSQL("INSERT INTO capture_tasks (id,tempPath,categoryId,categoryName,rootTreeUri,mediaKind,stage,targetUri,createdAt,updatedAt) VALUES ('task','source.jpg','home-cat','笔记','content://old-root','PHOTO','RETRY','content://old-target',1,2)")
            execSQL("INSERT INTO document_drafts (id,sourcePath,categoryId,categoryName,rootTreeUri,quadValue,effectValue,quarterTurns,stage,createdAt) VALUES ('draft','draft.jpg','home-cat','笔记','content://old-root','','ORIGINAL',0,'EDITING',3)")
            execSQL("PRAGMA user_version = 6")
        }
        initial.close()
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8).build()
        try {
            val db = migrated.openHelper.writableDatabase
            db.query("SELECT name FROM category_groups WHERE id='home'").use {
                assertEquals(true, it.moveToFirst()); assertEquals("常用", it.getString(0))
            }
            db.execSQL("INSERT INTO category_groups VALUES ('former-label','首页',1,2)")
            db.query("SELECT id,homeAtRoot,sortOrder,isActive,createdAt FROM categories ORDER BY id").use {
                assertEquals(true, it.moveToFirst()); assertEquals("custom-cat", it.getString(0)); assertEquals(0, it.getInt(1))
                assertEquals(true, it.moveToNext()); assertEquals("home-cat", it.getString(0)); assertEquals(1, it.getInt(1))
                assertEquals(7, it.getInt(2)); assertEquals(0, it.getInt(3)); assertEquals(123L, it.getLong(4))
            }
            for (table in listOf("capture_tasks", "document_drafts")) db.query("SELECT rootTreeUri,groupName FROM $table").use {
                assertEquals(true, it.moveToFirst()); assertEquals("content://old-root", it.getString(0)); assertEquals(true, it.isNull(1))
            }
            db.query("SELECT targetUri FROM capture_tasks").use {
                assertEquals(true, it.moveToFirst()); assertEquals("content://old-target", it.getString(0))
            }
        } finally { migrated.close() }
    }

    @Test fun migrate7To8PreservesLegacyJournalRowsAndDefaultsAnchorToNull() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(name)
        val initial = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        initial.openHelper.writableDatabase.apply {
            createLegacyDirectoryOperations(this)
            execSQL("INSERT INTO category_groups VALUES ('home','常用',-1,0),('trip','旅行',2,3)")
            execSQL("INSERT INTO categories VALUES ('cat','笔记',7,0,123,'home',1)")
            execSQL("INSERT INTO directory_operations VALUES ('native','TRANSFER_CATEGORY','cat','home','trip','content://root',NULL,'旅行','笔记','笔记','journal:native','FILESYSTEM_DONE',NULL,11,12,1)")
            execSQL("INSERT INTO directory_operations VALUES ('copy','MIGRATE_HOME_CATEGORY','cat','home','home','content://root',NULL,'常用','笔记','笔记','{\"phase\":\"COPYING\"}','COPYING','interrupted',21,22,NULL)")
            execSQL("PRAGMA user_version = 7")
        }
        initial.close()
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_7_8).build()
        try {
            val db = migrated.openHelper.writableDatabase
            db.query("PRAGMA user_version").use { assertEquals(true, it.moveToFirst()); assertEquals(8, it.getInt(0)) }
            db.query("SELECT id,kind,categoryId,sourceGroupId,targetGroupId,rootTreeUri,sourceGroupName,targetGroupName,sourceName,targetName,stateJson,stage,error,createdAt,updatedAt,slot,targetBeforeCategoryId FROM directory_operations ORDER BY id DESC").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(listOf("native", "TRANSFER_CATEGORY", "cat", "home", "trip", "content://root", null, "旅行", "笔记", "笔记", "journal:native", "FILESYSTEM_DONE", null, "11", "12", "1", null),
                    (0 until it.columnCount).map { index -> if (it.isNull(index)) null else it.getString(index) })
                assertEquals(true, it.moveToNext())
                assertEquals(listOf("copy", "MIGRATE_HOME_CATEGORY", "cat", "home", "home", "content://root", null, "常用", "笔记", "笔记", "{\"phase\":\"COPYING\"}", "COPYING", "interrupted", "21", "22", null, null),
                    (0 until it.columnCount).map { index -> if (it.isNull(index)) null else it.getString(index) })
                assertEquals(false, it.moveToNext())
            }
            db.query("SELECT sortOrder,isActive,createdAt,groupId,homeAtRoot FROM categories WHERE id='cat'").use {
                assertEquals(true, it.moveToFirst()); assertEquals(7, it.getInt(0)); assertEquals(0, it.getInt(1))
                assertEquals(123L, it.getLong(2)); assertEquals("home", it.getString(3)); assertEquals(1, it.getInt(4))
            }
            db.execSQL("INSERT INTO directory_operations (id,kind,sourceGroupId,targetGroupId,rootTreeUri,sourceName,targetName,stateJson,stage,createdAt,updatedAt) VALUES ('new','RENAME_GROUP','trip','trip','content://root','旅行','出行','','COMMITTED',31,32)")
            db.query("SELECT targetBeforeCategoryId FROM directory_operations WHERE id='new'").use {
                assertEquals(true, it.moveToFirst()); assertEquals(true, it.isNull(0))
            }
        } finally { migrated.close() }
    }

    private fun createLegacyDirectoryOperations(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE directory_operations")
        db.execSQL("CREATE TABLE directory_operations (id TEXT NOT NULL PRIMARY KEY,kind TEXT NOT NULL,categoryId TEXT,sourceGroupId TEXT NOT NULL,targetGroupId TEXT NOT NULL,rootTreeUri TEXT NOT NULL,sourceGroupName TEXT,targetGroupName TEXT,sourceName TEXT NOT NULL,targetName TEXT NOT NULL,stateJson TEXT NOT NULL,stage TEXT NOT NULL,error TEXT,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,slot INTEGER)")
        db.execSQL("CREATE UNIQUE INDEX index_directory_operations_slot ON directory_operations (slot)")
    }
}
