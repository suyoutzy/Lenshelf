package com.example.photocategorycamera

import android.content.Context
import androidx.room.Room
import com.example.photocategorycamera.data.AppDatabase
import com.example.photocategorycamera.data.CategoryRepository
import com.example.photocategorycamera.data.CategoryGroupRepository
import com.example.photocategorycamera.data.DirectoryOperationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import com.example.photocategorycamera.data.CaptureTaskRepository
import com.example.photocategorycamera.data.SettingsRepository
import com.example.photocategorycamera.data.DocumentDraftRepository
import com.example.photocategorycamera.document.DocumentProcessor
import com.example.photocategorycamera.storage.MediaStorage
import com.example.photocategorycamera.storage.SafPhotoStorage

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "photo-category-camera.db",
    ).addMigrations(
        AppDatabase.MIGRATION_1_2,
        AppDatabase.MIGRATION_2_3,
        AppDatabase.MIGRATION_3_4,
        AppDatabase.MIGRATION_4_5,
        AppDatabase.MIGRATION_5_6,
        AppDatabase.MIGRATION_6_7,
        AppDatabase.MIGRATION_7_8,
    ).build()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val categories = CategoryRepository(database.categoryDao(), database.categoryGroupDao())
    val groups = CategoryGroupRepository(database.categoryGroupDao())
    val settings = SettingsRepository(appContext)
    val mediaStorage: MediaStorage = SafPhotoStorage(appContext, settings)
    val documentProcessor = DocumentProcessor()
    val captureTasks = CaptureTaskRepository(appContext, database, settings, mediaStorage)
    val documents = DocumentDraftRepository(appContext, database, documentProcessor, captureTasks)
    val directoryOperations = DirectoryOperationRepository(appContext, database, settings, categories, groups, mediaStorage, appScope)
    val initialization = appScope.async {
        categories.ensureSeeded()
        directoryOperations.resumeInterrupted()
    }

    init {
        appScope.launch {
            initialization.await()
            // Once frozen old material finishes, migrate its now-idle category without a restart.
            combine(captureTasks.outstanding, documents.editable) { _, _ -> Unit }.collect {
                directoryOperations.migrateHomeCategories()
            }
        }
    }
}
