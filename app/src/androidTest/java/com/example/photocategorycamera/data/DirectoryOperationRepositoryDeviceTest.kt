package com.example.photocategorycamera.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.photocategorycamera.domain.*
import com.example.photocategorycamera.storage.MediaStorage
import com.example.photocategorycamera.storage.operations.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Every database, preference store and file in this suite belongs to a unique cache fixture. */
@RunWith(AndroidJUnit4::class)
class DirectoryOperationRepositoryDeviceTest {
    private class Fixture(app: Context, val directory: File, native: Boolean) {
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getApplicationContext(): Context = this
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "settings.preferences_pb") })
        val settings = SettingsRepository(context, store)
        val categories = CategoryRepository(db.categoryDao(), db.categoryGroupDao())
        val groups = CategoryGroupRepository(db.categoryGroupDao())
        val rootFile = File(directory, "root").apply { check(mkdir()) }
        val root = StorageRoot(Uri.fromFile(rootFile).toString(), "fixture", 1)
        val backend = FileBackend(native)
        val storage = FileStorage(backend)
        fun coordinator(hook: suspend () -> Unit = {}) = DirectoryOperationRepository(context, db, settings,
            categories, groups, storage, scope, backend, hook)
        suspend fun awaitFinished(id: String) = withTimeout(10_000) {
            while (db.directoryOperationDao().find(id)?.slot != null) delay(10)
        }
        suspend fun awaitReserved(): DirectoryOperationEntity {
            val id = withContext(Dispatchers.IO) {
                db.query("SELECT id FROM directory_operations ORDER BY createdAt DESC LIMIT 1", emptyArray()).use {
                    check(it.moveToFirst()); it.getString(0)
                }
            }
            return checkNotNull(db.directoryOperationDao().find(id))
        }
    }

    private fun fixture(native: Boolean = false, block: suspend (Fixture) -> Unit) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.cacheDir, "directory-coordinator-${UUID.randomUUID()}").apply { check(mkdir()) }
        val f = Fixture(app, directory, native)
        try {
            f.db.categoryGroupDao().ensureHome()
            f.settings.setStorageRoot(f.root)
            withTimeout(30_000) { block(f) }
        } finally {
            f.scope.coroutineContext.job.cancelAndJoin()
            f.db.close()
            check(directory.deleteRecursively())
        }
    }

    @Test fun bulkReorderRejectsStaleDuplicateWrongGroupAndWrongStateWithoutWrites() = fixture { f ->
        val ops = f.coordinator()
        val target = ops.createGroup("target").getOrThrow()
        val a = ops.createCategory("a", "home").getOrThrow()
        val b = ops.createCategory("b", "home").getOrThrow()
        val inactive = ops.createCategory("inactive", "home").getOrThrow()
        ops.setCategoryActive(inactive, false).getOrThrow()
        val outside = ops.createCategory("outside", target.id).getOrThrow()
        val expected = listOf(a.id, b.id)
        val original = f.categories.getAll()
        assertTrue(ops.reorderCategories("home", true, expected.reversed(), expected).isFailure)
        assertTrue(ops.reorderCategories("home", true, expected, listOf(a.id, a.id)).isFailure)
        assertTrue(ops.reorderCategories("home", true, expected, listOf(a.id, outside.id)).isFailure)
        assertTrue(ops.reorderCategories("home", true, expected, listOf(a.id, inactive.id)).isFailure)
        assertEquals(original, f.categories.getAll())
        ops.reorderCategories("home", true, expected, expected.reversed()).getOrThrow()
        assertEquals(listOf(b.id, a.id, inactive.id), f.categories.getAll("home").map { it.id })
        assertEquals(original.first { it.id == inactive.id }, f.categories.findById(inactive.id))
        assertEquals(original.first { it.id == outside.id }, f.categories.findById(outside.id))
    }

    @Test fun customGroupReorderKeepsHomeFixedAndRejectsStaleOrForeignIdsAtomically() = fixture { f ->
        val ops = f.coordinator()
        val a = ops.createGroup("a").getOrThrow()
        val b = ops.createGroup("b").getOrThrow()
        val expected = listOf(a.id, b.id)
        val original = f.groups.getAll()
        assertTrue(ops.reorderGroups(expected.reversed(), expected).isFailure)
        assertTrue(ops.reorderGroups(expected, listOf(a.id, a.id)).isFailure)
        assertTrue(ops.reorderGroups(listOf("home") + expected, listOf("home") + expected.reversed()).isFailure)
        assertTrue(ops.reorderGroups(expected, listOf("home", b.id)).isFailure)
        assertEquals(original, f.groups.getAll())
        ops.reorderGroups(expected, expected.reversed()).getOrThrow()
        assertEquals(listOf("home", b.id, a.id), f.groups.getAll().map { it.id })
        assertEquals(original.first(), f.groups.getAll().first())
    }

    @Test fun reorderedMovingCategoryIsRejectedWhenCaptureOrDraftProtectsItsPosition() = fixture { f ->
        val ops = f.coordinator()
        val a = ops.createCategory("a", "home").getOrThrow()
        val b = ops.createCategory("b", "home").getOrThrow()
        val expected = listOf(a.id, b.id)
        val original = f.categories.getAll()
        val frozen = CaptureTaskEntity("frozen", "fixture.jpg", a.id, a.name, f.root.treeUri,
            "PHOTO", "FAILED", createdAt = 1, updatedAt = 2)
        f.db.captureTaskDao().insert(frozen)
        assertTrue(ops.reorderCategories("home", true, expected, expected.reversed()).isFailure)
        assertEquals(original, f.categories.getAll())
        f.db.captureTaskDao().delete(frozen.id)
        val draft = DocumentDraftEntity("draft", "fixture.jpg", b.id, b.name, f.root.treeUri,
            "", "ORIGINAL", 0, "EDITING", 1, null, 1)
        f.db.documentDraftDao().insert(draft)
        assertTrue(ops.reorderCategories("home", true, expected, expected.reversed()).isFailure)
        assertEquals(original, f.categories.getAll())
    }

    @Test fun groupOrderRemainsAvailableWithOutstandingCaptureAndDraftLocationsFrozen() = fixture { f ->
        val ops = f.coordinator()
        val a = ops.createGroup("a").getOrThrow()
        val b = ops.createGroup("b").getOrThrow()
        val captured = ops.createCategory("captured", a.id).getOrThrow()
        val drafted = ops.createCategory("drafted", b.id).getOrThrow()
        val task = CaptureTaskEntity("task", "fixture.jpg", captured.id, captured.name, f.root.treeUri,
            "PHOTO", "FAILED", createdAt = 1, updatedAt = 2, groupId = a.id, groupName = a.name)
        val draft = DocumentDraftEntity("draft", "draft.jpg", drafted.id, drafted.name, f.root.treeUri,
            "", "ORIGINAL", 0, "EDITING", 1, null, 1, b.id, b.name)
        f.db.captureTaskDao().insert(task)
        f.db.documentDraftDao().insert(draft)
        val categoriesBefore = f.categories.getAll()
        ops.reorderGroups(listOf(a.id, b.id), listOf(b.id, a.id)).getOrThrow()
        assertEquals(listOf("home", b.id, a.id), f.groups.getAll().map { it.id })
        assertEquals(task, f.db.captureTaskDao().find(task.id))
        assertEquals(draft, f.db.documentDraftDao().find(draft.id))
        assertEquals(categoriesBefore, f.categories.getAll())
        ops.reorderGroup(a, -1).getOrThrow()
        assertEquals(listOf("home", a.id, b.id), f.groups.getAll().map { it.id })
    }

    private suspend fun transferAtAnchor(f: Fixture, active: Boolean, interrupt: Boolean, invalidAnchor: String? = null) {
        val interrupted = CompletableDeferred<Unit>()
        val ops = if (interrupt) f.coordinator { interrupted.complete(Unit); throw CancellationException("process stopped") }
            else f.coordinator()
        val target = ops.createGroup("target").getOrThrow()
        val opposite = ops.createCategory("opposite", target.id).getOrThrow()
        val first = ops.createCategory("first", target.id).getOrThrow()
        val anchor = ops.createCategory("anchor", target.id).getOrThrow()
        val moving = ops.createCategory("moving", "home").getOrThrow()
        ops.setCategoryActive(opposite, !active).getOrThrow()
        listOf(first, anchor, moving).forEach { ops.setCategoryActive(it, active).getOrThrow() }
        val unchanged = f.categories.findById(opposite.id)
        File(f.rootFile, "常用/moving/source.txt").writeText("retained")
        assertTrue(ops.transferCategory(moving, target.id, opposite.id).isFailure)
        assertTrue(ops.transferCategory(moving, target.id, moving.id).isFailure)
        assertNull(f.db.directoryOperationDao().pending())
        ops.transferCategory(moving, target.id, anchor.id).getOrThrow()
        if (interrupt) interrupted.await()
        val row = f.awaitReserved()
        assertEquals(anchor.id, row.targetBeforeCategoryId)
        if (interrupt) {
            assertEquals("FILESYSTEM_DONE", row.stage)
            assertTrue(ops.reorderCategories(target.id, active, listOf(first.id, anchor.id), listOf(anchor.id, first.id)).isFailure)
            assertTrue(ops.reorderCategories("home", active, listOf(moving.id), listOf(moving.id)).isFailure)
            when (invalidAnchor) {
                "state" -> f.db.categoryDao().setActive(anchor.id, !active)
                "missing" -> f.db.categoryDao().deleteById(anchor.id)
                "group" -> f.db.categoryDao().update(checkNotNull(f.db.categoryDao().findById(anchor.id)).copy(groupId = "home"))
            }
            f.coordinator().resumeInterrupted()
        }
        f.awaitFinished(row.id)
        val section = f.categories.getAll(target.id).filter { it.isActive == active }
        assertEquals(if (invalidAnchor != null) listOf(first.id, moving.id) else listOf(first.id, moving.id, anchor.id), section.map { it.id })
        assertEquals(section.indices.toList(), section.map { it.sortOrder })
        assertEquals(unchanged, f.categories.findById(opposite.id))
        assertEquals(active, f.categories.findById(moving.id)!!.isActive)
        assertEquals("retained", File(f.rootFile, "target/moving/source.txt").readText())
        f.coordinator().resumeInterrupted()
        assertEquals(1, f.categories.getAll().count { it.id == moving.id })
        assertEquals(section, f.categories.getAll(target.id).filter { it.isActive == active })
    }

    @Test fun copyTransferInsertsBeforeActiveAnchorAndLeavesInactiveSectionUntouched() = fixture { transferAtAnchor(it, true, false) }
    @Test fun copyTransferInsertsBeforeInactiveAnchorAndLeavesActiveSectionUntouched() = fixture { transferAtAnchor(it, false, false) }
    @Test fun nativeTransferRecoversDurableAnchorExactlyOnce() = fixture(native = true) { f ->
        transferAtAnchor(f, true, true)
        assertEquals(1, f.backend.moves)
    }
    @Test fun copyTransferRecoversDurableInactiveAnchorExactlyOnce() = fixture { f ->
        transferAtAnchor(f, false, true)
        assertEquals(0, f.backend.moves)
    }
    @Test fun recoveredTransferAppendsWhenAnchorChangesState() = fixture(native = true) { transferAtAnchor(it, true, true, "state") }
    @Test fun recoveredTransferAppendsWhenAnchorIsDeleted() = fixture { transferAtAnchor(it, false, true, "missing") }
    @Test fun recoveredTransferAppendsWhenAnchorChangesGroup() = fixture(native = true) { transferAtAnchor(it, true, true, "group") }

    @Test fun nullAnchorAppendsActiveTransferWithinActiveSectionOnly() = fixture { f ->
        val ops = f.coordinator()
        val target = ops.createGroup("target").getOrThrow()
        val first = ops.createCategory("first", target.id).getOrThrow()
        val inactive = ops.createCategory("inactive", target.id).getOrThrow()
        ops.setCategoryActive(inactive, false).getOrThrow()
        val source = ops.createCategory("source", "home").getOrThrow()
        val before = f.categories.findById(inactive.id)
        ops.transferCategory(source, target.id).getOrThrow()
        val row = f.awaitReserved()
        assertNull(row.targetBeforeCategoryId)
        f.awaitFinished(row.id)
        assertEquals(listOf(first.id, source.id, inactive.id), f.categories.getAll(target.id).map { it.id })
        assertEquals(before, f.categories.findById(inactive.id))
    }

    @Test fun copyTransferPreservesInactiveIdAllContentAndAppendsInDestination() = fixture { f ->
        val ops = f.coordinator()
        val group = ops.createGroup("课程").getOrThrow()
        val first = ops.createCategory("已有", group.id).getOrThrow()
        ops.setCategoryActive(first, false).getOrThrow()
        val source = ops.createCategory("笔记", CategoryGroup.HOME_ID).getOrThrow()
        ops.setCategoryActive(source, false).getOrThrow()
        val folder = File(f.rootFile, "常用/${source.name}")
        File(folder, ".secret").writeText("hidden bytes")
        File(folder, "nested").apply { mkdir() }.resolve("notes.txt").writeText("ordinary bytes")
        ops.transferCategory(source, group.id).getOrThrow()
        val row = f.awaitReserved()
        f.awaitFinished(row.id)
        val transferred = f.categories.findById(source.id)!!
        assertEquals(group.id, transferred.groupId)
        assertFalse(transferred.isActive)
        assertTrue(transferred.sortOrder > first.sortOrder)
        assertEquals("课程/笔记", transferred.directoryLabel)
        assertEquals(CategoryGroup.HOME_ID, f.settings.settings.first().selectedGroupId)
        assertFalse(folder.exists())
        assertEquals("hidden bytes", File(f.rootFile, "课程/笔记/.secret").readText())
        assertEquals("ordinary bytes", File(f.rootFile, "课程/笔记/nested/notes.txt").readText())
        assertEquals(0, f.backend.moves)
    }

    @Test fun collisionsAndImportRespectCurrentGroupIncludingUnregisteredDirectories() = fixture { f ->
        val ops = f.coordinator()
        val source = ops.createCategory("笔记", CategoryGroup.HOME_ID).getOrThrow()
        ops.setCategoryActive(source, false).getOrThrow()
        assertTrue(ops.createGroup("笔记").isSuccess)
        File(f.rootFile, "occupied").writeText("outside")
        assertTrue(ops.createGroup("occupied").isFailure)
        val group = ops.createGroup("课程").getOrThrow()
        assertTrue(ops.createCategory("课程", CategoryGroup.HOME_ID).isSuccess)
        File(f.rootFile, "课程/笔记").mkdir()
        assertTrue(ops.transferCategory(source, group.id).isFailure)
        assertNull(f.db.directoryOperationDao().pending())
        assertTrue(File(f.rootFile, "常用/笔记").exists())
        File(f.rootFile, "课程/数学").mkdir()
        assertEquals(listOf("数学", "笔记"), ops.importCategories(group.id).getOrThrow().createdNames.sorted())
        assertFalse(ops.importCategories(CategoryGroup.HOME_ID).getOrThrow().createdNames.contains("课程"))
        assertTrue(ops.createCategory("笔记", group.id).isFailure)
        assertEquals(4, f.categories.getAll().size)
    }

    @Test fun groupDeletionChecksInactiveRecordsAndHiddenUnregisteredFiles() = fixture { f ->
        val ops = f.coordinator()
        val group = ops.createGroup("课程").getOrThrow()
        val cat = ops.createCategory("数学", group.id).getOrThrow()
        ops.setCategoryActive(cat, false).getOrThrow()
        assertTrue(ops.deleteGroup(group).isFailure)
        ops.deleteCategory(cat, false).getOrThrow()
        assertTrue(ops.deleteGroup(group).isFailure) // Retained physical category.
        check(File(f.rootFile, "课程/数学").delete())
        val hidden = File(f.rootFile, "课程/.unknown").apply { writeText("external bytes") }
        assertTrue(ops.deleteGroup(group).isFailure)
        assertEquals("external bytes", hidden.readText())
        check(hidden.delete())
        ops.deleteGroup(group).getOrThrow()
        assertNull(f.groups.findById(group.id))
    }

    @Test fun missingInactiveSourceFailsBeforeReservingTheGlobalOperationSlot() = fixture { f ->
        val ops = f.coordinator()
        val group = ops.createGroup("课程").getOrThrow()
        val cat = ops.createCategory("笔记", CategoryGroup.HOME_ID).getOrThrow()
        ops.setCategoryActive(cat, false).getOrThrow()
        check(File(f.rootFile, "常用/笔记").delete())
        assertTrue(ops.transferCategory(cat, group.id).isFailure)
        assertTrue(ops.renameCategory(cat, "改名").isFailure)
        assertNull(f.db.directoryOperationDao().pending())
        ops.setCategoryActive(cat, true).getOrThrow()
        assertTrue(File(f.rootFile, "常用/笔记").isDirectory)
    }

    @Test fun largeManifestUsesAtomicFileWhileRoomAndUnrelatedCaptureGuardsRemainReadable() = fixture { f ->
        val ops = f.coordinator()
        val cat = ops.createCategory("其他", CategoryGroup.HOME_ID).getOrThrow()
        val entries = listOf(DirectoryManifestEntry("", DirectoryNode("source/category", "root", "category", true), "")) + (0 until 8_000).map { index ->
            val uri = "content://fixture/" + "long-name-".repeat(16) + index
            DirectoryManifestEntry("file-$index", DirectoryNode(uri, uri, "file-$index", false), "a".repeat(64))
        }
        val state = DirectoryMutationState(DirectoryMutationRequest("TRANSFER_CATEGORY", "source", "target", "category", "category"), "COPYING", entries)
        val journal = DirectoryOperationJournal(f.directory)
        assertTrue(DirectoryMutationStateCodec.encode(state).toByteArray().size > 2_097_152)
        val reference = journal.write("large-manifest", state)
        assertTrue(reference.length < 100)
        val row = DirectoryOperationEntity("large-manifest", "TRANSFER_CATEGORY", "another", "home", "home", f.root.treeUri,
            null, null, "moving", "moving", reference, "COPYING", null, 1, 1)
        f.db.directoryOperationDao().insert(row)
        assertEquals(state, journal.read(row.id, f.db.directoryOperationDao().pending()!!.stateJson))
        assertEquals(cat.id, f.db.latestCaptureCategory(cat.id).id)
    }

    @Test fun journalReservationBlocksMovingCaptureAndRootSwitchButAllowsOtherCapture() = fixture { f ->
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ops = f.coordinator { reached.complete(Unit); release.await() }
        val group = ops.createGroup("课程").getOrThrow()
        val moving = ops.createCategory("笔记", CategoryGroup.HOME_ID).getOrThrow()
        val other = ops.createCategory("生活", CategoryGroup.HOME_ID).getOrThrow()
        val captures = CaptureTaskRepository(f.context, f.db, f.settings, f.storage, f.scope)
        ops.transferCategory(moving, group.id).getOrThrow()
        reached.await()
        assertTrue(captures.reserve(moving, f.root, MediaKind.PHOTO) { error("Locked capture must not create source") }.isFailure)
        assertTrue(ops.configureRoot(Uri.fromFile(File(f.directory, "another-root"))).isFailure)
        assertTrue(ops.configureRoot(Uri.parse(f.root.treeUri)).isSuccess) // Regrant does not mutate any directory.
        assertTrue(ops.renameGroup(group, "更名").isFailure)
        val reservation = captures.reserve(other.copy(name = "stale UI name"), f.root, MediaKind.VIDEO) {
            File(f.directory, "camera.mp4").apply { createNewFile() }
        }.getOrThrow()
        assertEquals("生活", reservation.category.name)
        assertEquals(CategoryGroup.HOME_ID, f.db.captureTaskDao().find(reservation.id)!!.groupId)
        val row = f.awaitReserved()
        release.complete(Unit)
        f.awaitFinished(row.id)
        // A capture that won the same transaction boundary now prevents a directory operation.
        assertTrue(ops.transferCategory(other, group.id).isFailure)
    }

    @Test fun filesystemDoneProcessInterruptionRecoversMetadataWithoutRepeatingNativeMove() = fixture(native = true) { f ->
        val interrupted = CompletableDeferred<Unit>()
        val ops = f.coordinator { interrupted.complete(Unit); throw CancellationException("simulated process termination") }
        val group = ops.createGroup("课程").getOrThrow()
        val cat = ops.createCategory("笔记", CategoryGroup.HOME_ID).getOrThrow()
        File(f.rootFile, "常用/笔记/file.txt").writeText("original")
        ops.transferCategory(cat, group.id).getOrThrow()
        interrupted.await()
        val row = f.awaitReserved()
        assertEquals("FILESYSTEM_DONE", row.stage)
        assertNull(row.error)
        assertEquals(CategoryGroup.HOME_ID, f.categories.findById(cat.id)!!.groupId)
        f.coordinator().resumeInterrupted()
        f.awaitFinished(row.id)
        assertEquals(1, f.backend.moves)
        assertEquals(group.id, f.categories.findById(cat.id)!!.groupId)
        assertEquals("original", File(f.rootFile, "课程/笔记/file.txt").readText())
        assertEquals("COMMITTED", f.db.directoryOperationDao().find(row.id)!!.stage)
    }

    @Test fun failedMetadataCommitWaitsForManualRetryAndRenameRetainsWholeGroup() = fixture(native = true) { f ->
        var fail = true
        val ops = f.coordinator { if (fail) throw java.io.IOException("simulated metadata failure") }
        val group = ops.createGroup("课程").getOrThrow()
        val cat = ops.createCategory("笔记", group.id).getOrThrow()
        File(f.rootFile, "课程/.outside").writeText("unregistered")
        ops.renameGroup(group, "课程学习").getOrThrow()
        val row = f.awaitReserved()
        withTimeout(10_000) { while (f.db.directoryOperationDao().find(row.id)!!.error == null) delay(10) }
        f.coordinator().resumeInterrupted()
        assertEquals("课程", f.groups.findById(group.id)!!.name)
        assertTrue(ops.createCategory("暂缓", group.id).isFailure)
        fail = false
        ops.retry(row.id).getOrThrow()
        f.awaitFinished(row.id)
        assertEquals("课程学习/笔记", f.categories.findById(cat.id)!!.directoryLabel)
        assertEquals("unregistered", File(f.rootFile, "课程学习/.outside").readText())
        assertFalse(File(f.rootFile, "课程").exists())
    }

    private suspend fun legacyCategory(f: Fixture, name: String = "旧笔记"): Category {
        f.db.categoryDao().insert(CategoryEntity("legacy", name, 7, false, 123, homeAtRoot = true))
        File(f.rootFile, "$name/nested").mkdirs()
        File(f.rootFile, "$name/.hidden").writeText("hidden bytes")
        File(f.rootFile, "$name/nested/data.bin").writeBytes(byteArrayOf(0, 1, -1, 4))
        return f.categories.findById("legacy")!!
    }

    private suspend fun awaitHomeMigration(f: Fixture) = withTimeout(10_000) {
        while (f.categories.getAll().any { it.needsHomeMigration } || f.db.directoryOperationDao().pending() != null) delay(10)
    }

    private suspend fun verifyHomeMigration(f: Fixture, native: Boolean) {
        val old = legacyCategory(f)
        f.coordinator().migrateHomeCategories().getOrThrow()
        awaitHomeMigration(f)
        val moved = f.categories.findById(old.id)!!
        assertEquals(old.copy(groupName = "常用"), moved)
        assertFalse(File(f.rootFile, old.name).exists())
        assertEquals("hidden bytes", File(f.rootFile, "常用/${old.name}/.hidden").readText())
        assertArrayEquals(byteArrayOf(0, 1, -1, 4), File(f.rootFile, "常用/${old.name}/nested/data.bin").readBytes())
        assertEquals(if (native) 1 else 0, f.backend.moves)
    }

    @Test fun commonMigrationCopiesAllContentAndPreservesCategoryIdentityAndOrder() = fixture { verifyHomeMigration(it, false) }
    @Test fun commonMigrationUsesNativeMoveWhenSupported() = fixture(native = true) { verifyHomeMigration(it, true) }

    @Test fun commonMigrationResumesAfterFilesystemDoneWithoutMovingAgain() = fixture(native = true) { f ->
        val old = legacyCategory(f)
        val interrupted = CompletableDeferred<Unit>()
        val ops = f.coordinator { interrupted.complete(Unit); throw CancellationException("process stopped") }
        ops.migrateHomeCategories().getOrThrow()
        interrupted.await()
        val row = f.awaitReserved()
        assertEquals("FILESYSTEM_DONE", row.stage)
        assertTrue(f.categories.findById(old.id)!!.needsHomeMigration)
        f.coordinator().resumeInterrupted()
        awaitHomeMigration(f)
        assertEquals(1, f.backend.moves)
        assertEquals("常用", f.categories.findById(old.id)!!.groupName)
    }

    @Test fun commonMigrationWaitsForLegacyMediaAndKeepsFrozenTargetUnchanged() = fixture { f ->
        val old = legacyCategory(f)
        val frozen = CaptureTaskEntity("old-task", "original.jpg", old.id, old.name, f.root.treeUri,
            "PHOTO", "FAILED", "content://original-target", createdAt = 1, updatedAt = 2)
        f.db.captureTaskDao().insert(frozen)
        val ops = f.coordinator()
        ops.migrateHomeCategories().getOrThrow()
        assertEquals(frozen, f.db.captureTaskDao().find(frozen.id))
        assertTrue(File(f.rootFile, old.name).isDirectory)
        assertFalse(File(f.rootFile, "常用/${old.name}").exists())
        assertNotNull(ops.migrationIssue.value)
        assertTrue(ops.configureRoot(Uri.fromFile(File(f.directory, "other"))).isFailure)
        f.db.captureTaskDao().delete(frozen.id)
        ops.migrateHomeCategories().getOrThrow()
        awaitHomeMigration(f)
    }

    @Test fun commonMigrationRefusesExistingTargetWithoutMergingOrDeletingEitherSide() = fixture { f ->
        val old = legacyCategory(f)
        File(f.rootFile, "常用/${old.name}").mkdirs()
        File(f.rootFile, "常用/${old.name}/external.txt").writeText("external")
        val ops = f.coordinator()
        assertTrue(ops.migrateHomeCategories().isFailure)
        assertNull(f.db.directoryOperationDao().pending())
        assertTrue(f.categories.findById(old.id)!!.needsHomeMigration)
        assertEquals("hidden bytes", File(f.rootFile, "${old.name}/.hidden").readText())
        assertEquals("external", File(f.rootFile, "常用/${old.name}/external.txt").readText())
    }

    @Test fun legacyCategoryNamedCommonMustBeRenamedBeforeContainerIsCreated() = fixture { f ->
        val old = legacyCategory(f, "常用")
        val ops = f.coordinator()
        assertTrue(ops.migrateHomeCategories().isFailure)
        assertFalse(File(f.rootFile, "常用/常用").exists())
        ops.renameCategory(old, "旧常用").getOrThrow()
        awaitHomeMigration(f)
        assertEquals("hidden bytes", File(f.rootFile, "常用/旧常用/.hidden").readText())
    }

    @Test fun legacyRootNamesRemainReservedEvenWhenDirectoryIsMissing() = fixture { f ->
        f.db.categoryDao().insert(CategoryEntity("legacy", "Foo", 0, false, 1, homeAtRoot = true))
        val ops = f.coordinator()
        assertTrue(ops.createGroup("foo").isFailure)
        val other = ops.createGroup("Other").getOrThrow()
        assertTrue(ops.renameGroup(other, "FOO").isFailure)
        // Defend again when recovering old data that already contains an ambiguous namespace.
        f.db.categoryGroupDao().insert(CategoryGroupEntity("conflict", "Foo", 2, 3))
        File(f.rootFile, "Foo").mkdir()
        File(f.rootFile, "Foo/custom.txt").writeText("custom bytes")
        assertTrue(ops.migrateHomeCategories().isFailure)
        assertEquals("custom bytes", File(f.rootFile, "Foo/custom.txt").readText())
        assertFalse(File(f.rootFile, "常用/Foo").exists())
        val ambiguous = f.categories.findById("legacy")!!
        assertTrue(ops.renameCategory(ambiguous, "Renamed").isFailure)
        assertTrue(ops.transferCategory(ambiguous, other.id).isFailure)
        assertTrue(ops.deleteCategory(ambiguous, true).isFailure)
        assertTrue(ops.renameGroup(f.groups.findById("conflict")!!, "Renamed").isFailure)
        assertEquals("custom bytes", File(f.rootFile, "Foo/custom.txt").readText())
    }

    @Test fun oldVirtualHomeTransferJournalCompletesThenMigratesToPhysicalCommon() = fixture(native = true) { f ->
        val ops = f.coordinator()
        val group = ops.createGroup("课程").getOrThrow()
        val cat = ops.createCategory("旧笔记", group.id).getOrThrow()
        File(f.rootFile, "课程/旧笔记/original.txt").writeText("frozen bytes")
        f.db.directoryOperationDao().insert(DirectoryOperationEntity("old-v6", "TRANSFER_CATEGORY", cat.id,
            group.id, CategoryGroup.HOME_ID, f.root.treeUri, group.name, null, cat.name, cat.name,
            "", "RESERVED", null, 1, 1))
        ops.resumeInterrupted()
        withTimeout(10_000) {
            while (f.categories.findById(cat.id)?.groupName != "常用" || f.db.directoryOperationDao().pending() != null) delay(10)
        }
        assertEquals("frozen bytes", File(f.rootFile, "常用/旧笔记/original.txt").readText())
        assertFalse(File(f.rootFile, "旧笔记").exists())
        assertEquals(2, f.backend.moves)
    }

    private class FileBackend(private val native: Boolean) : DirectoryBackend {
        var moves = 0
        fun file(uri: String) = File(java.net.URI(uri))
        fun node(file: File) = DirectoryNode(Uri.fromFile(file).toString(), file.canonicalPath, file.name,
            file.isDirectory, supportsMove = native, supportsRename = native)
        override fun children(parentUri: String) = (file(parentUri).listFiles() ?: error("Missing parent")).map(::node)
        override fun read(uri: String) = file(uri).inputStream()
        override fun write(uri: String) = file(uri).outputStream()
        override fun create(parentUri: String, name: String, directory: Boolean, mimeType: String): DirectoryNode {
            val target = file(parentUri).resolve(name)
            check(if (directory) target.mkdir() else target.createNewFile())
            return node(target)
        }
        override fun delete(uri: String) { check(file(uri).delete()) }
        override fun move(uri: String, sourceParentUri: String, targetParentUri: String): DirectoryNode {
            moves++
            val source = file(uri)
            val target = file(targetParentUri).resolve(source.name)
            check(source.renameTo(target))
            return node(target)
        }
        override fun rename(uri: String, name: String): DirectoryNode {
            val source = file(uri)
            val target = source.parentFile!!.resolve(name)
            check(source.renameTo(target))
            return node(target)
        }
    }

    private class FileStorage(private val backend: FileBackend) : MediaStorage {
        private fun parent(root: Uri, group: String?) = if (group == null) backend.file(root.toString()) else backend.file(root.toString()).resolve(group)
        override fun hasPersistedAccess(treeUri: Uri) = true
        override suspend fun prepareRoot(treeUri: Uri, categories: List<Category>) = Result.success(StorageRoot(treeUri.toString(), "fixture", 1))
        override suspend fun groupDirectoryUri(treeUri: Uri, groupName: String?) = runCatching {
            val parent = parent(treeUri, groupName)
            check(parent.isDirectory)
            Uri.fromFile(parent)
        }
        override suspend fun createGroupDirectory(treeUri: Uri, name: String) = runCatching { check(parent(treeUri, name).mkdir()); Unit }
        override suspend fun deleteEmptyGroupDirectory(treeUri: Uri, name: String) = runCatching { check(parent(treeUri, name).delete()); Unit }
        override suspend fun listCategoryDirectories(treeUri: Uri, groupName: String?) = runCatching {
            parent(treeUri, groupName).listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted()
        }
        override suspend fun countCategoryMedia(treeUri: Uri, categoryNames: List<String>, groupName: String?) = Result.success(emptyMap<String, Int>())
        override suspend fun ensureCategory(treeUri: Uri, categoryName: String, groupName: String?) = runCatching {
            val dir = parent(treeUri, groupName).resolve(categoryName)
            check(dir.isDirectory || dir.mkdirs()); Unit
        }
        override suspend fun categoryDirectoryUri(treeUri: Uri, categoryName: String, groupName: String?) = Result.success(Uri.fromFile(parent(treeUri, groupName).resolve(categoryName)))
        override suspend fun renameCategoryDirectory(treeUri: Uri, oldName: String, newName: String, groupName: String?) = Result.failure<Unit>(UnsupportedOperationException())
        override suspend fun deleteCategoryDirectoryWithContents(treeUri: Uri, categoryName: String, groupName: String?) = runCatching { check(parent(treeUri, groupName).resolve(categoryName).deleteRecursively()); Unit }
        override suspend fun storeTempMedia(treeUri: Uri, tempFile: File, category: Category, mediaKind: MediaKind) = error("No saving in this fixture")
        override suspend fun reserveTarget(treeUri: Uri, category: Category, mediaKind: MediaKind) = Result.failure<Uri>(java.io.IOException("fixture"))
        override suspend fun storeTempMediaToTarget(targetUri: Uri, tempFile: File, category: Category, mediaKind: MediaKind) = error("No saving in this fixture")
        override suspend fun retry(treeUri: Uri, pending: PendingMedia) = error("No legacy retry in this fixture")
    }
}
