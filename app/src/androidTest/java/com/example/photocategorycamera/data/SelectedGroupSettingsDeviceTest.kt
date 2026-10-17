package com.example.photocategorycamera.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.photocategorycamera.domain.PendingMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SelectedGroupSettingsDeviceTest {
    @Test fun remembersSelectedGroupAndDefaultsLegacySettingsToHome() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "groups-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            val repository = SettingsRepository(context, store)
            assertEquals("home", repository.settings.first().selectedGroupId)
            repository.setSelectedGroupId("trip")
            assertEquals("trip", SettingsRepository(context, store).settings.first().selectedGroupId)
            repository.setSelectedGroupId("")
            assertEquals("home", repository.settings.first().selectedGroupId)
            store.edit {
                it[stringPreferencesKey("pending_path")] = "/legacy.jpg"
                it[stringPreferencesKey("pending_category_id")] = "receipt"
                it[stringPreferencesKey("pending_category_name")] = "票据"
            }
            assertEquals("home", repository.settings.first().pendingCapture?.groupId)
            assertEquals(null, repository.settings.first().pendingCapture?.groupName)
            repository.setPendingCapture(PendingMedia("/trip.jpg", "receipt", "票据", groupId = "trip", groupName = "旅行"))
            val restored = SettingsRepository(context, store).settings.first().pendingCapture!!
            assertEquals("/trip.jpg", restored.tempPath)
            assertEquals("trip", restored.groupId)
            assertEquals("旅行", restored.groupName)
            repository.clearPendingCapture()
            assertEquals(null, repository.settings.first().pendingCapture)
            store.edit {
                it[stringPreferencesKey("pending_path")] = "/legacy-next.jpg"
                it[stringPreferencesKey("pending_category_id")] = "receipt"
                it[stringPreferencesKey("pending_category_name")] = "票据"
            }
            assertEquals("home", repository.settings.first().pendingCapture?.groupId)
            assertEquals(null, repository.settings.first().pendingCapture?.groupName)
        } finally {
            scope.cancel()
            file.delete()
        }
    }
}
