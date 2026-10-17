package com.example.photocategorycamera.storage

import android.net.Uri
import com.example.photocategorycamera.domain.CaptureResult
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.MediaKind
import com.example.photocategorycamera.domain.PendingMedia
import com.example.photocategorycamera.domain.StorageRoot
import java.io.File

interface MediaStorage {
    suspend fun prepareRoot(treeUri: Uri, categories: List<Category>): Result<StorageRoot>
    suspend fun listCategoryDirectories(treeUri: Uri, groupName: String? = null): Result<List<String>>
    suspend fun countCategoryMedia(treeUri: Uri, categoryNames: List<String>, groupName: String? = null): Result<Map<String, Int>>
    suspend fun ensureCategory(treeUri: Uri, categoryName: String, groupName: String? = null): Result<Unit>
    suspend fun categoryDirectoryUri(treeUri: Uri, categoryName: String, groupName: String? = null): Result<Uri>
    suspend fun renameCategoryDirectory(treeUri: Uri, oldName: String, newName: String, groupName: String? = null): Result<Unit>
    suspend fun deleteCategoryDirectoryWithContents(treeUri: Uri, categoryName: String, groupName: String? = null): Result<Unit>
    /** Resolves the existing parent without creating missing folders. */
    suspend fun groupDirectoryUri(treeUri: Uri, groupName: String? = null): Result<Uri> =
        Result.failure(UnsupportedOperationException("目录提供方不支持一级文件夹"))
    suspend fun createGroupDirectory(treeUri: Uri, name: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("目录提供方不支持一级文件夹"))
    suspend fun deleteEmptyGroupDirectory(treeUri: Uri, name: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("目录提供方不支持一级文件夹"))
    suspend fun storeTempMedia(
        treeUri: Uri,
        tempFile: File,
        category: Category,
        mediaKind: MediaKind,
    ): CaptureResult
    /** Creates the final SAF document before bytes are written so its URI can be persisted. */
    suspend fun reserveTarget(treeUri: Uri, category: Category, mediaKind: MediaKind): Result<Uri>
    /** Idempotently writes or verifies a previously reserved SAF document. */
    suspend fun storeTempMediaToTarget(
        targetUri: Uri,
        tempFile: File,
        category: Category,
        mediaKind: MediaKind,
    ): CaptureResult
    suspend fun retry(treeUri: Uri, pending: PendingMedia): CaptureResult
    fun hasPersistedAccess(treeUri: Uri): Boolean
}

typealias PhotoStorage = MediaStorage
