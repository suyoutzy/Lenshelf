package com.example.photocategorycamera.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.photocategorycamera.domain.DocumentEffect
import com.example.photocategorycamera.domain.PhotoDateStampFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.UUID

class CategoryCameraSettingsDeviceTest {
    @Test fun categoriesStartAtDefaultsAndChangesRemainIndependentAcrossRepositoryInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "category-camera-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            val settings = SettingsRepository(context, store)
            // Older global choices must not replace the new per-category defaults.
            settings.setPhotoDateStampEnabled(false)
            settings.setCameraGridEnabled(true)
            settings.setPostCaptureNoteEnabled(true)
            settings.setVideoStabilizationEnabled(false)
            val defaults = CategoryCameraSettings()
            assertEquals(true, defaults.timeStampEnabled)
            assertEquals(PhotoDateStampFormat.DATE, defaults.timeStampFormat)
            assertEquals(false, defaults.gridEnabled)
            assertEquals(false, defaults.postCaptureNoteEnabled)
            assertEquals(true, defaults.videoStabilizationEnabled)
            assertEquals(CategoryFlashMode.OFF, defaults.flashMode)
            assertEquals(DocumentEffect.ORIGINAL, defaults.documentEffect)
            assertEquals(defaults, settings.categoryCameraSettings("a").first())
            assertEquals(defaults, settings.categoryCameraSettings("b").first())
            val changed = defaults.copy(timeStampEnabled=false, timeStampFormat=PhotoDateStampFormat.DATE_TIME,
                gridEnabled=true, postCaptureNoteEnabled=true, videoStabilizationEnabled=false,
                flashMode=CategoryFlashMode.ON, documentEffect=DocumentEffect.ENHANCED)
            settings.updateCategoryCameraSettings("a") { changed }
            assertEquals(changed, SettingsRepository(context,store).categoryCameraSettings("a").first())
            assertEquals(defaults, settings.categoryCameraSettings("b").first())
            settings.updateCategoryCameraSettings("b") { it.copy(gridEnabled=true) }
            assertEquals(changed, settings.categoryCameraSettings("a").first())
            assertEquals(defaults.copy(gridEnabled=true), settings.categoryCameraSettings("b").first())
        } finally { scope.cancel();file.delete() }
    }
}
