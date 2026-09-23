package com.example.photocategorycamera.ui

import com.example.photocategorycamera.data.AppSettings
import com.example.photocategorycamera.data.CaptureTaskEntity
import com.example.photocategorycamera.data.CaptureTaskStage
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.FolderOpenMode
import com.example.photocategorycamera.domain.MediaKind
import com.example.photocategorycamera.domain.PendingMedia
import com.example.photocategorycamera.domain.StorageRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUiStateMapperTest {
    private val root = StorageRoot("content://root", "Lenshelf", 1L)
    private val category = Category("category-1", "学习", 0, true, 1L)

    @Test
    fun `summarizes legacy pending retry and failed tasks without counting failures as pending`() {
        val settings = AppSettings(
            storageRoot = root,
            pendingCapture = PendingMedia("legacy.jpg", category.id, category.name),
        )
        val retry = task("retry", "旅行", CaptureTaskStage.RETRY)
        val failed = task("failed", "工作", CaptureTaskStage.FAILED, "文件损坏")

        val state = AppUiStateMapper.create(
            categories = listOf(category),
            settings = settings,
            tasks = listOf(retry, failed),
            busy = true,
            message = "处理中",
        )

        assertTrue(state.hasPendingCapture)
        assertTrue(state.canRetryCapture)
        assertEquals(2, state.pendingCaptureCount)
        assertEquals(category.name, state.pendingCategoryName)
        assertEquals(1, state.failedCaptureCount)
        assertEquals("文件损坏", state.failedCaptureError)
        assertTrue(state.busy)
        assertEquals("处理中", state.message)
    }

    @Test
    fun `uses retry category before failed category when no legacy pending item exists`() {
        val state = AppUiStateMapper.create(
            categories = listOf(category),
            settings = AppSettings(storageRoot = root),
            tasks = listOf(
                task("failed", "工作", CaptureTaskStage.FAILED, "失败"),
                task("retry", "旅行", CaptureTaskStage.RETRY),
            ),
            busy = false,
            message = null,
        )

        assertEquals("旅行", state.pendingCategoryName)
        assertEquals(1, state.pendingCaptureCount)
        assertEquals(1, state.failedCaptureCount)
    }

    @Test
    fun `copies settings and only applies file counts from the active root`() {
        val base = AppUiStateMapper.create(
            categories = listOf(category),
            settings = AppSettings(
                storageRoot = root,
                folderOpenMode = FolderOpenMode.XIAOMI,
                cameraGridEnabled = true,
                motionPhotoEnabled = true,
                videoStabilizationEnabled = false,
            ),
            tasks = emptyList(),
            busy = false,
            message = null,
        )

        assertFalse(base.hasPendingCapture)
        assertEquals(FolderOpenMode.XIAOMI, base.folderOpenMode)
        assertTrue(base.cameraGridEnabled)
        assertTrue(base.motionPhotoEnabled)
        assertFalse(base.videoStabilizationEnabled)
        assertEquals(
            mapOf(category.id to 7),
            AppUiStateMapper.withFileCounts(
                base,
                CategoryFileCounts(root.treeUri, mapOf(category.id to 7)),
            ).categoryFileCounts,
        )
        assertTrue(
            AppUiStateMapper.withFileCounts(
                base,
                CategoryFileCounts("content://other", mapOf(category.id to 9)),
            ).categoryFileCounts.isEmpty(),
        )
    }

    private fun task(
        id: String,
        categoryName: String,
        stage: CaptureTaskStage,
        error: String? = null,
    ) = CaptureTaskEntity(
        id = id,
        tempPath = "$id.tmp",
        categoryId = "$id-category",
        categoryName = categoryName,
        rootTreeUri = root.treeUri,
        mediaKind = MediaKind.PHOTO.name,
        stage = stage.name,
        error = error,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
