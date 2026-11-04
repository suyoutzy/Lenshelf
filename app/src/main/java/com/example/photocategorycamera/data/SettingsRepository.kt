package com.example.photocategorycamera.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import com.example.photocategorycamera.domain.PendingCapture
import com.example.photocategorycamera.domain.MediaKind
import com.example.photocategorycamera.domain.FolderOpenMode
import com.example.photocategorycamera.domain.PhotoDateStampFormat
import com.example.photocategorycamera.domain.StorageRoot
import com.example.photocategorycamera.domain.CategoryGroup
import com.example.photocategorycamera.domain.DocumentEffect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

enum class CategoryFlashMode { OFF, AUTO, ON;
    fun next(): CategoryFlashMode = when (this) { OFF -> AUTO; AUTO -> ON; ON -> OFF }
}

data class CategoryCameraSettings(
    val gridEnabled: Boolean = false,
    val timeStampEnabled: Boolean = true,
    val timeStampFormat: PhotoDateStampFormat = PhotoDateStampFormat.DATE,
    val postCaptureNoteEnabled: Boolean = false,
    val videoStabilizationEnabled: Boolean = true,
    val flashMode: CategoryFlashMode = CategoryFlashMode.OFF,
    val documentEffect: DocumentEffect = DocumentEffect.ORIGINAL,
)

data class AppSettings(
    val storageRoot: StorageRoot? = null,
    val pendingCapture: PendingCapture? = null,
    val folderOpenMode: FolderOpenMode = FolderOpenMode.SYSTEM,
    val cameraGridEnabled: Boolean = false,
    val photoDateStampEnabled: Boolean = false,
    val photoDateStampFormat: PhotoDateStampFormat = PhotoDateStampFormat.DATE,
    val postCaptureNoteEnabled: Boolean = false,
    val videoStabilizationEnabled: Boolean = true,
    val selectedGroupId: String = CategoryGroup.HOME_ID,
) {
    val timeStampEnabled: Boolean get() = photoDateStampEnabled
    val timeStampFormat: com.example.photocategorycamera.domain.TimeStampFormat get() = photoDateStampFormat
}

class SettingsRepository(context: Context, private val store: DataStore<Preferences> = context.settingsDataStore) {
    private object Keys {
        val rootUri = stringPreferencesKey("root_uri")
        val rootName = stringPreferencesKey("root_name")
        val rootAuthorizedAt = longPreferencesKey("root_authorized_at")
        val pendingPath = stringPreferencesKey("pending_path")
        val pendingCategoryId = stringPreferencesKey("pending_category_id")
        val pendingCategoryName = stringPreferencesKey("pending_category_name")
        val pendingMediaKind = stringPreferencesKey("pending_media_kind")
        val pendingGroupId = stringPreferencesKey("pending_group_id")
        val pendingGroupName = stringPreferencesKey("pending_group_name")
        val folderOpenMode = stringPreferencesKey("folder_open_mode")
        val cameraGridEnabled = booleanPreferencesKey("camera_grid_enabled")
        val photoDateStampEnabled = booleanPreferencesKey("photo_date_stamp_enabled")
        val photoDateStampFormat = stringPreferencesKey("photo_date_stamp_format")
        val postCaptureNoteEnabled = booleanPreferencesKey("post_capture_note_enabled")
        val videoStabilizationEnabled = booleanPreferencesKey("video_stabilization_enabled")
        val selectedGroupId = stringPreferencesKey("selected_group_id")
    }

    private val preferences = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
    val settings: Flow<AppSettings> = preferences.map(::toSettings)

    private fun categoryKey(id: String, name: String) = "category_camera_${id}_$name"

    private fun categoryCameraSettings(id: String, p: Preferences): CategoryCameraSettings {
        val defaults = CategoryCameraSettings()
        return CategoryCameraSettings(
            gridEnabled = p[booleanPreferencesKey(categoryKey(id,"grid"))] ?: defaults.gridEnabled,
            timeStampEnabled = p[booleanPreferencesKey(categoryKey(id,"timestamp"))] ?: defaults.timeStampEnabled,
            timeStampFormat = PhotoDateStampFormat.entries.firstOrNull { it.name == p[stringPreferencesKey(categoryKey(id,"timestamp_format"))] } ?: defaults.timeStampFormat,
            postCaptureNoteEnabled = p[booleanPreferencesKey(categoryKey(id,"note"))] ?: defaults.postCaptureNoteEnabled,
            videoStabilizationEnabled = p[booleanPreferencesKey(categoryKey(id,"stabilization"))] ?: defaults.videoStabilizationEnabled,
            flashMode = CategoryFlashMode.entries.firstOrNull { it.name == p[stringPreferencesKey(categoryKey(id,"flash"))] } ?: defaults.flashMode,
            documentEffect = DocumentEffect.entries.firstOrNull { it.name == p[stringPreferencesKey(categoryKey(id,"document_effect"))] } ?: defaults.documentEffect,
        )
    }

    fun categoryCameraSettings(id: String): Flow<CategoryCameraSettings> {
        require(id.isNotBlank())
        return preferences.map { categoryCameraSettings(id,it) }
    }

    /** Read the root and category recipe from the same durable preferences snapshot. */
    suspend fun captureSettings(id: String): Pair<AppSettings, CategoryCameraSettings> =
        preferences.first().let { toSettings(it) to categoryCameraSettings(id,it) }

    suspend fun updateCategoryCameraSettings(id: String, update: (CategoryCameraSettings) -> CategoryCameraSettings) {
        require(id.isNotBlank())
        store.edit { p ->
            val value = update(categoryCameraSettings(id,p))
            p[booleanPreferencesKey(categoryKey(id,"grid"))] = value.gridEnabled
            p[booleanPreferencesKey(categoryKey(id,"timestamp"))] = value.timeStampEnabled
            p[stringPreferencesKey(categoryKey(id,"timestamp_format"))] = value.timeStampFormat.name
            p[booleanPreferencesKey(categoryKey(id,"note"))] = value.postCaptureNoteEnabled
            p[booleanPreferencesKey(categoryKey(id,"stabilization"))] = value.videoStabilizationEnabled
            p[stringPreferencesKey(categoryKey(id,"flash"))] = value.flashMode.name
            p[stringPreferencesKey(categoryKey(id,"document_effect"))] = value.documentEffect.name
        }
    }

    suspend fun setStorageRoot(root: StorageRoot) {
        store.edit {
            it[Keys.rootUri] = root.treeUri
            it[Keys.rootName] = root.displayName
            it[Keys.rootAuthorizedAt] = root.authorizedAt
        }
    }

    suspend fun setPendingCapture(pending: PendingCapture) {
        store.edit {
            it[Keys.pendingPath] = pending.tempPath
            it[Keys.pendingCategoryId] = pending.categoryId
            it[Keys.pendingCategoryName] = pending.categoryName
            it[Keys.pendingMediaKind] = pending.mediaKind.name
            it[Keys.pendingGroupId] = pending.groupId
            if (pending.groupId == CategoryGroup.HOME_ID || pending.groupName == null) {
                it.remove(Keys.pendingGroupName)
            } else {
                it[Keys.pendingGroupName] = pending.groupName
            }
        }
    }

    suspend fun clearPendingCapture() {
        store.edit {
            it.remove(Keys.pendingPath)
            it.remove(Keys.pendingCategoryId)
            it.remove(Keys.pendingCategoryName)
            it.remove(Keys.pendingMediaKind)
            it.remove(Keys.pendingGroupId)
            it.remove(Keys.pendingGroupName)
        }
    }

    suspend fun setFolderOpenMode(mode: FolderOpenMode) {
        store.edit { it[Keys.folderOpenMode] = mode.name }
    }

    suspend fun setPhotoDateStampEnabled(enabled: Boolean) {
        store.edit { it[Keys.photoDateStampEnabled] = enabled }
    }

    suspend fun setPhotoDateStampFormat(format: PhotoDateStampFormat) {
        store.edit { it[Keys.photoDateStampFormat] = format.name }
    }

    suspend fun setTimeStampEnabled(enabled: Boolean) = setPhotoDateStampEnabled(enabled)
    suspend fun setTimeStampFormat(format: com.example.photocategorycamera.domain.TimeStampFormat) = setPhotoDateStampFormat(format)
    suspend fun setPostCaptureNoteEnabled(enabled: Boolean) { store.edit { it[Keys.postCaptureNoteEnabled] = enabled } }

    suspend fun setCameraGridEnabled(enabled: Boolean) {
        store.edit { it[Keys.cameraGridEnabled] = enabled }
    }

    /** Idempotent upgrade cleanup; no removed feature remains in runtime settings. */
    suspend fun migrateObsoleteCameraPreferences() {
        store.edit {
            it.remove(booleanPreferencesKey("motion_photo_enabled"))
            it.remove(booleanPreferencesKey("pending_is_motion_photo"))
            if (it[Keys.pendingMediaKind] == "MOTION_PHOTO") it[Keys.pendingMediaKind] = MediaKind.PHOTO.name
        }
    }

    suspend fun setVideoStabilizationEnabled(enabled: Boolean) {
        store.edit { it[Keys.videoStabilizationEnabled] = enabled }
    }

    suspend fun setSelectedGroupId(groupId: String) {
        store.edit { it[Keys.selectedGroupId] = groupId.ifBlank { CategoryGroup.HOME_ID } }
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
                ?: MediaKind.PHOTO
            val groupId = preferences[Keys.pendingGroupId]?.takeIf { it.isNotBlank() } ?: CategoryGroup.HOME_ID
            PendingCapture(path, id, name, mediaKind, groupId,
                if (groupId == CategoryGroup.HOME_ID) null else preferences[Keys.pendingGroupName],
            )
        }
        val folderOpenMode = preferences[Keys.folderOpenMode]
            ?.let { stored -> FolderOpenMode.entries.firstOrNull { it.name == stored } }
            ?: FolderOpenMode.SYSTEM
        return AppSettings(
            storageRoot = root,
            pendingCapture = pending,
            folderOpenMode = folderOpenMode,
            cameraGridEnabled = preferences[Keys.cameraGridEnabled] ?: false,
            photoDateStampEnabled = preferences[Keys.photoDateStampEnabled] ?: false,
            photoDateStampFormat = PhotoDateStampFormat.entries.firstOrNull { it.name == preferences[Keys.photoDateStampFormat] } ?: PhotoDateStampFormat.DATE,
            postCaptureNoteEnabled = preferences[Keys.postCaptureNoteEnabled] ?: false,
            videoStabilizationEnabled = preferences[Keys.videoStabilizationEnabled] ?: true,
            selectedGroupId = preferences[Keys.selectedGroupId]?.takeIf { it.isNotBlank() } ?: CategoryGroup.HOME_ID,
        )
    }
}
