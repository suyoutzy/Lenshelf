package com.example.photocategorycamera.ui

import com.example.photocategorycamera.data.AppSettings
import com.example.photocategorycamera.data.CaptureTaskEntity
import com.example.photocategorycamera.data.CaptureTaskStage
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CategoryGroup
import com.example.photocategorycamera.data.DirectoryOperationEntity
import com.example.photocategorycamera.data.DocumentDraftEntity
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

    @Test fun `legacy home waits for migration without blocking rename conflict resolution`() {
        val legacy = category.copy(groupName = null)
        val fresh = category.copy(id = "fresh")
        val state = AppUiStateMapper.create(listOf(legacy, fresh), AppSettings(storageRoot = root), emptyList(), false, null)
        assertEquals(setOf(legacy.id), state.directoryLockedCategoryIds)
        assertFalse(state.isCategoryMutationLocked(legacy))
        assertTrue(state.rootSwitchLocked)
        assertEquals(listOf("常用/学习"), state.homeMigrationLabels)
        assertEquals("Lenshelf/学习", categoryDirectoryPath(root, legacy))
        assertEquals("Lenshelf/常用/学习", categoryDirectoryPath(root, fresh))
    }

    @Test fun `home selection excludes categories of custom groups while keeping inactive home categories`() {
        val inactive = category.copy(id = "inactive", isActive = false)
        val elsewhere = category.copy(id = "other", groupId = "work", groupName = "工作")
        val state = AppUiStateMapper.create(listOf(category, inactive, elsewhere),
            AppSettings(storageRoot = root), emptyList(), false, null)
        assertEquals(listOf("category-1", "inactive"), state.categories.map { it.id })
    }

    @Test fun `saved group selection filters by id and a missing group falls back home`() {
        val work = CategoryGroup("work", "科研", 1, 1L)
        val custom = category.copy(id = "work-category", groupId = work.id, groupName = work.name)
        val all = listOf(category, custom)
        val selected = AppUiStateMapper.create(all, AppSettings(selectedGroupId = work.id),
            emptyList(), false, null, listOf(work))
        assertEquals(work.id, selected.selectedGroup.id)
        assertEquals(listOf(custom), selected.categories)
        val missing = AppUiStateMapper.create(all, AppSettings(selectedGroupId = "deleted"),
            emptyList(), false, null, listOf(work))
        assertEquals(CategoryGroup.HOME_ID, missing.selectedGroup.id)
        assertEquals(listOf(category), missing.categories)
        assertEquals(CategoryGroup.HOME_ID, selected.groups.first().id)
    }

    @Test fun `file counts from a previous selected group never replace current group counts`() {
        val state = AppUiState(storageRoot = root,
            selectedGroup = CategoryGroup("work", "科研", 1, 1))
        val stale = CategoryFileCounts(root.treeUri, mapOf(category.id to 7), CategoryGroup.HOME_ID)
        assertTrue(AppUiStateMapper.withFileCounts(state, stale).categoryFileCounts.isEmpty())
    }

    @Test fun `failed transfers keep source category and both groups locked but unrelated capture usable`() {
        val target = CategoryGroup("work", "科研", 1, 1)
        val unrelated = category.copy(id = "unrelated")
        val operation = operation("TRANSFER_CATEGORY", category.id, CategoryGroup.HOME_ID, target.id,
            error = "校验失败")
        val state = AppUiStateMapper.create(listOf(category, unrelated), AppSettings(storageRoot = root),
            emptyList(), false, null, listOf(target), listOf(operation))
        assertEquals(setOf(category.id), state.directoryLockedCategoryIds)
        assertTrue(state.isCategoryMutationLocked(category))
        assertFalse(state.isCategoryMutationLocked(unrelated))
        assertEquals(setOf(CategoryGroup.HOME_ID, target.id), state.lockedGroupIds)
        assertEquals(setOf(CategoryGroup.HOME_ID, target.id), state.directoryOperationGroupIds)
        assertEquals(category.id, state.directoryOperations.single().categoryId)
        assertEquals(target.id, state.directoryOperations.single().targetGroupId)
        assertTrue(state.rootSwitchLocked)
        assertEquals("校验失败", state.directoryOperations.single().error)
        assertEquals("Lenshelf/学习", state.directoryOperations.single().sourcePath)
        assertEquals("Lenshelf/科研/学习", state.directoryOperations.single().targetPath)
    }

    @Test fun `group rename blocks child captures and capture failures block mutation only`() {
        val group = CategoryGroup("work", "科研", 1, 1)
        val custom = category.copy(groupId = group.id, groupName = group.name)
        val operation = operation("RENAME_GROUP", null, group.id, group.id)
            .copy(sourceName = "科研", targetName = "研究")
        val state = AppUiStateMapper.create(listOf(category, custom), AppSettings(selectedGroupId = group.id),
            listOf(task("failed", "学习", CaptureTaskStage.FAILED).copy(categoryId = "home-id")),
            false, null, listOf(group), listOf(operation))
        assertEquals(setOf(category.id), state.directoryLockedCategoryIds)
        assertTrue("home-id" in state.mutationLockedCategoryIds)
        assertFalse("home-id" in state.directoryLockedCategoryIds)
    }

    @Test fun `queue labels retain frozen group names for categories outside current group`() {
        val queued = task("retry", "学习", CaptureTaskStage.RETRY)
            .copy(groupId = "work", groupName = "科研")
        val state = AppUiStateMapper.create(listOf(category), AppSettings(), listOf(queued), false, null)
        assertEquals(listOf("科研/学习"), state.pendingCategoryLabels)
    }

    @Test fun `legacy pending retains its frozen group in labels and group locks`() {
        val pending = PendingMedia("legacy.jpg", category.id, category.name, groupId = "work", groupName = "科研")
        val state = AppUiStateMapper.create(listOf(category), AppSettings(pendingCapture = pending), emptyList(), false, null)
        assertEquals(listOf("科研/学习"), state.pendingCategoryLabels)
        assertTrue("work" in state.lockedGroupIds)
        assertFalse(CategoryGroup.HOME_ID in state.lockedGroupIds)
    }

    @Test fun `editable document locks its category mutations and group while preserving capture usability`() {
        val draft = DocumentDraftEntity("draft", "fixture.jpg", category.id, category.name, root.treeUri,
            createdAt = 1, groupId = "work", groupName = "科研")
        val state = AppUiStateMapper.withDocumentDraft(AppUiState(), draft)
        assertTrue(state.isCategoryMutationLocked(category))
        assertFalse(category.id in state.directoryLockedCategoryIds)
        assertTrue("work" in state.lockedGroupIds)
        assertEquals("科研/学习", state.documentDraftLabel)
    }

    @Test fun `completed directory journal never locks UI or appears as pending`() {
        val completed = operation("TRANSFER_CATEGORY", category.id, CategoryGroup.HOME_ID, "work").copy(slot = null)
        val state = AppUiStateMapper.create(listOf(category), AppSettings(), emptyList(), false, null,
            operations = listOf(completed))
        assertTrue(state.directoryOperations.isEmpty())
        assertTrue(state.directoryOperationGroupIds.isEmpty())
        assertFalse(state.rootSwitchLocked)
        assertFalse(state.isCategoryMutationLocked(category))
    }

    @Test fun `saving media group locks do not masquerade as directory operations`() {
        val saving = task("saving", "学习", CaptureTaskStage.SAVING)
        val state = AppUiStateMapper.create(listOf(category), AppSettings(), listOf(saving), false, null)
        assertTrue(state.lockedGroupIds.isNotEmpty())
        assertTrue(state.directoryOperationGroupIds.isEmpty())
    }

    @Test fun `ordinary saving tasks remain visible without a retry or failure`() {
        val saving = task("saving", "学习", CaptureTaskStage.SAVING)
        val state = AppUiStateMapper.create(listOf(category), AppSettings(), listOf(saving), false, null)
        assertTrue(state.hasPendingCapture)
        assertEquals(1, state.pendingCaptureCount)
        assertFalse(state.canRetryCapture)
        assertEquals("学习", state.pendingCategoryName)
    }

    @Test fun `source and target operation paths include full external storage root and omit physical Home folder`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3APictures%2FLenshelf"
        val settings = AppSettings(storageRoot = root.copy(treeUri = uri))
        val moving = operation("TRANSFER_CATEGORY", category.id, CategoryGroup.HOME_ID, "work").copy(rootTreeUri = uri)
        val state = AppUiStateMapper.create(listOf(category), settings, emptyList(), false, null, operations = listOf(moving))
        assertEquals("/storage/emulated/0/Pictures/Lenshelf/学习", state.directoryOperations.single().sourcePath)
        assertEquals("/storage/emulated/0/Pictures/Lenshelf/科研/学习", state.directoryOperations.single().targetPath)
    }

    private fun operation(kind: String, categoryId: String?, sourceGroupId: String,
        targetGroupId: String, error: String? = null) = DirectoryOperationEntity(
        "op", kind, categoryId, sourceGroupId, targetGroupId, root.treeUri,
        null, if (targetGroupId == "work") "科研" else null, "学习", "学习",
        "{}", "COPYING", error, 1, 1)

    @Test fun `document processing reserves a slot and processing errors expose retry`() {
        val state = AppUiStateMapper.create(listOf(category), AppSettings(storageRoot = root),
            listOf(task("processing", "学习", CaptureTaskStage.DOCUMENT_PROCESSING),
                task("retry", "课件", CaptureTaskStage.DOCUMENT_RETRY, "处理失败")), false, null)
        assertEquals(2, state.pendingCaptureCount)
        assertEquals("课件", state.pendingCategoryName)
        assertTrue(state.hasPendingCapture)
        assertTrue(state.canRetryCapture)
        assertEquals(0, state.failedCaptureCount)
    }

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
                videoStabilizationEnabled = false,
            ),
            tasks = emptyList(),
            busy = false,
            message = null,
        )

        assertFalse(base.hasPendingCapture)
        assertEquals(FolderOpenMode.XIAOMI, base.folderOpenMode)
        assertTrue(base.cameraGridEnabled)
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
