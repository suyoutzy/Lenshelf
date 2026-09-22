package com.example.photocategorycamera

import android.content.Context
import androidx.room.Room
import com.example.photocategorycamera.data.AppDatabase
import com.example.photocategorycamera.data.CategoryRepository
import com.example.photocategorycamera.data.CaptureTaskRepository
import com.example.photocategorycamera.data.SettingsRepository
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
    ).build()

    val categories = CategoryRepository(database.categoryDao())
    val settings = SettingsRepository(appContext)
    val mediaStorage: MediaStorage = SafPhotoStorage(appContext, settings)
    val captureTasks = CaptureTaskRepository(appContext, database.captureTaskDao(), settings, mediaStorage)
}
