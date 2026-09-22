package com.example.photocategorycamera.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.photocategorycamera.domain.PendingCapture
import com.example.photocategorycamera.domain.MediaKind
import com.example.photocategorycamera.domain.FolderOpenMode
import com.example.photocategorycamera.domain.StorageRoot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

data class AppSettings(
    val storageRoot: StorageRoot? = null,
    val pendingCapture: PendingCapture? = null,
    val folderOpenMode: FolderOpenMode = FolderOpenMode.SYSTEM,
    val cameraGridEnabled: Boolean = false,
    val motionPhotoEnabled: Boolean = false,
    val videoStabilizationEnabled: Boolean = true,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val rootUri = stringPreferencesKey("root_uri")
        val rootName = stringPreferencesKey("root_name")
        val rootAuthorizedAt = longPreferencesKey("root_authorized_at")
        val pendingPath = stringPreferencesKey("pending_path")
        val pendingCategoryId = stringPreferencesKey("pending_category_id")
        val pendingCategoryName = stringPreferencesKey("pending_category_name")
        val pendingIsMotionPhoto = booleanPreferencesKey("pending_is_motion_photo")
        val pendingMediaKind = stringPreferencesKey("pending_media_kind")
        val folderOpenMode = stringPreferencesKey("folder_open_mode")
        val cameraGridEnabled = booleanPreferencesKey("camera_grid_enabled")
        val motionPhotoEnabled = booleanPreferencesKey("motion_photo_enabled")
        val videoStabilizationEnabled = booleanPreferencesKey("video_stabilization_enabled")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::toSettings)

    suspend fun setStorageRoot(root: StorageRoot) {
        context.settingsDataStore.edit {
            it[Keys.rootUri] = root.treeUri
            it[Keys.rootName] = root.displayName
            it[Keys.rootAuthorizedAt] = root.authorizedAt
        }
    }

    suspend fun setPendingCapture(pending: PendingCapture) {
        context.settingsDataStore.edit {
            it[Keys.pendingPath] = pending.tempPath
            it[Keys.pendingCategoryId] = pending.categoryId
            it[Keys.pendingCategoryName] = pending.categoryName
            it[Keys.pendingIsMotionPhoto] = pending.isMotionPhoto
            it[Keys.pendingMediaKind] = pending.mediaKind.name
        }
    }

    suspend fun clearPendingCapture() {
        context.settingsDataStore.edit {
            it.remove(Keys.pendingPath)
            it.remove(Keys.pendingCategoryId)
            it.remove(Keys.pendingCategoryName)
            it.remove(Keys.pendingIsMotionPhoto)
            it.remove(Keys.pendingMediaKind)
        }
    }

    suspend fun setFolderOpenMode(mode: FolderOpenMode) {
        context.settingsDataStore.edit { it[Keys.folderOpenMode] = mode.name }
    }

    suspend fun setCameraGridEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.cameraGridEnabled] = enabled }
    }

    suspend fun setMotionPhotoEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.motionPhotoEnabled] = enabled }
    }

    suspend fun setVideoStabilizationEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.videoStabilizationEnabled] = enabled }
    }

    private fun toSettings(preferences: Preferences): AppSettings {
        val root = preferences[Keys.rootUri]?.let { uri ->
            StorageRoot(
                treeUri = uri,
                displayName = preferences[Keys.rootName].orEmpty(),
                authorizedAt = preferences[Keys.rootAuthorizedAt] ?: 0L,
            )
        }
        val pending = preferences[Keys.pendingPath]?.let { path ->
            val id = preferences[Keys.pendingCategoryId] ?: return@let null
            val name = preferences[Keys.pendingCategoryName] ?: return@let null
            val mediaKind = preferences[Keys.pendingMediaKind]
                ?.let { stored -> MediaKind.entries.firstOrNull { it.name == stored } }
                ?: if (preferences[Keys.pendingIsMotionPhoto] == true) MediaKind.MOTION_PHOTO else MediaKind.PHOTO
            PendingCapture(path, id, name, mediaKind)
        }
        val folderOpenMode = preferences[Keys.folderOpenMode]
            ?.let { stored -> FolderOpenMode.entries.firstOrNull { it.name == stored } }
            ?: FolderOpenMode.SYSTEM
        return AppSettings(
            storageRoot = root,
            pendingCapture = pending,
            folderOpenMode = folderOpenMode,
            cameraGridEnabled = preferences[Keys.cameraGridEnabled] ?: false,
            motionPhotoEnabled = preferences[Keys.motionPhotoEnabled] ?: false,
            videoStabilizationEnabled = preferences[Keys.videoStabilizationEnabled] ?: true,
        )
    }
}
