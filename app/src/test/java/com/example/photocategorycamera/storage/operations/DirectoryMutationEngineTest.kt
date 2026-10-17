package com.example.photocategorycamera.storage.operations

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DirectoryMutationEngineTest {
    private val request = DirectoryMutationRequest("TRANSFER_CATEGORY", "root", "group", "Photos", "Photos")

    @Test fun copiesHiddenNestedAndNonMediaContentBeforeRemovingSource() = runBlocking {
        val fs = FakeDirectoryBackend()
        fs.fixture()
        val done = DirectoryMutationEngine(fs).execute(request) {}
        assertEquals("FILESYSTEM_DONE", done.phase)
        assertNull(fs.child("root", "Photos"))
        val target = fs.child("group", "Photos")!!
        assertEquals("secret", fs.text(fs.child(target.uri, ".hidden")!!.uri))
        val nested = fs.child(target.uri, "nested")!!
        assertEquals("notes", fs.text(fs.child(nested.uri, "notes.txt")!!.uri))
    }

    @Test fun writeFailurePreservesSourceAndResumesOwnedPartialTarget() = runBlocking {
        val fs = FakeDirectoryBackend().apply { fixture(); failWrite = true }
        var journal: DirectoryMutationState? = null
        expectFailure { DirectoryMutationEngine(fs).execute(request) { journal = it } }
        assertNotNull(fs.child("root", "Photos"))
        assertNotNull(journal)
        fs.failWrite = false
        val done = DirectoryMutationEngine(fs).execute(request, journal) { journal = it }
        assertEquals("FILESYSTEM_DONE", done.phase)
        assertNull(fs.child("root", "Photos"))
    }

    @Test fun corruptReadbackNeverDeletesSource() = runBlocking {
        val fs = FakeDirectoryBackend().apply { fixture(); corruptWrites = true }
        expectFailure { DirectoryMutationEngine(fs).execute(request) {} }
        assertEquals("secret", fs.text(fs.child("source", ".hidden")!!.uri))
    }

    @Test fun preexistingDestinationIsNeverAdoptedOrOverwritten() = runBlocking {
        val fs = FakeDirectoryBackend().apply { fixture(); add("collision", "group", "Photos", true) }
        expectFailure { DirectoryMutationEngine(fs).execute(request) {} }
        assertNotNull(fs.child("root", "Photos"))
        assertEquals("collision", fs.child("group", "Photos")!!.uri)
    }

    @Test fun nativeMoveThatThrowsAfterSuccessIsReconciledWithoutSecondMove() = runBlocking {
        val fs = FakeDirectoryBackend().apply { fixture(native = true); failAfterMove = true }
        var journal: DirectoryMutationState? = null
        expectFailure { DirectoryMutationEngine(fs).execute(request) { journal = it } }
        assertNotNull(journal)
        val done = DirectoryMutationEngine(fs).execute(request, journal) { journal = it }
        assertEquals("FILESYSTEM_DONE", done.phase)
        assertEquals(1, fs.moves)
    }

    @Test fun resumesAfterEveryPersistedCheckpointIncludingDeletion() = runBlocking {
        var count = 0
        DirectoryMutationEngine(FakeDirectoryBackend().apply { fixture() }).execute(request) { count++ }
        for (interruptAt in 1..count) {
            val fs = FakeDirectoryBackend().apply { fixture() }
            var journal: DirectoryMutationState? = null
            var step = 0
            expectFailure { DirectoryMutationEngine(fs).execute(request) {
                journal = it
                if (++step == interruptAt) throw IOException("Process interrupted")
            } }
            val done = DirectoryMutationEngine(fs).execute(request, journal) { journal = it }
            assertEquals("checkpoint $interruptAt", "FILESYSTEM_DONE", done.phase)
            assertNull(fs.child("root", "Photos"))
        }
    }

    @Test fun sourceChangedWhileCopyingPreservesAllSourceDocuments() = runBlocking {
        val fs = FakeDirectoryBackend().apply { fixture(); afterWrite = { add("new", "source", "new.txt", false, "new") } }
        expectFailure { DirectoryMutationEngine(fs).execute(request) {} }
        assertNotNull(fs.child("source", ".hidden"))
        assertNotNull(fs.child("source", "new.txt"))
    }

    @Test fun deleteThatThrowsAfterSuccessResumesWithoutRepeatingDeletion() = runBlocking {
        val fs = FakeDirectoryBackend().apply { fixture(); failAfterDelete = true }
        var journal: DirectoryMutationState? = null
        expectFailure { DirectoryMutationEngine(fs).execute(request) { journal = it } }
        fs.failAfterDelete = false
        assertEquals("FILESYSTEM_DONE", DirectoryMutationEngine(fs).execute(request, journal) {}.phase)
    }

    @Test fun createThatThrowsAfterSuccessKeepsUnownedTargetAndSource() = runBlocking {
        val fs = FakeDirectoryBackend().apply { fixture(); failAfterCreate = true }
        var journal: DirectoryMutationState? = null
        expectFailure { DirectoryMutationEngine(fs).execute(request) { journal = it } }
        fs.failAfterCreate = false
        expectFailure { DirectoryMutationEngine(fs).execute(request, journal) {} }
        assertNotNull(fs.child("root", "Photos"))
        assertNotNull(fs.child("group", "Photos"))
    }

    @Test fun renameRecoveryHandlesLostResponse() = runBlocking {
        val fs = FakeDirectoryBackend().apply { fixture(native = true); failAfterRename = true }
        val rename = request.copy(kind = "RENAME_CATEGORY", targetParentUri = "root", targetName = "Renamed")
        var journal: DirectoryMutationState? = null
        expectFailure { DirectoryMutationEngine(fs).execute(rename) { journal = it } }
        assertEquals("FILESYSTEM_DONE", DirectoryMutationEngine(fs).execute(rename, journal) {}.phase)
        assertNotNull(fs.child("root", "Renamed"))
    }

    @Test fun nativeMoveWithChangedIdentityAndLostResponseReconcilesWithoutWritingOrDeleting() = runBlocking {
        val fs = FakeDirectoryBackend().apply { fixture(native = true); failAfterMove = true }
        var journal: DirectoryMutationState? = null
        expectFailure { DirectoryMutationEngine(fs).execute(request) { journal = it } }
        val item = fs.items.getValue("source")
        item.node = item.node.copy(identity = "changed-after-native-move")
        assertEquals("FILESYSTEM_DONE", DirectoryMutationEngine(fs).execute(request, journal) {}.phase)
        assertNotNull(fs.child("group", "Photos"))
        assertEquals(1, fs.moves)
        assertEquals("secret", fs.text("hidden"))
    }

    @Test fun rejectsPartialVirtualAndAliasedNodes() = runBlocking {
        for (unsafe in listOf("partial", "virtual", "alias")) {
            val fs = FakeDirectoryBackend().apply { fixture() }
            val item = fs.items.getValue("hidden")
            item.node = when (unsafe) {
                "partial" -> item.node.copy(partial = true)
                "virtual" -> item.node.copy(virtual = true)
                else -> item.node.copy(identity = "source")
            }
            expectFailure { DirectoryMutationEngine(fs).execute(request) {} }
            assertNull(fs.child("group", "Photos"))
        }
    }

    @Test fun rejectsMoveIntoOwnDescendantBeforeCreatingTarget() = runBlocking {
        val fs = FakeDirectoryBackend().apply { fixture() }
        expectFailure { DirectoryMutationEngine(fs).execute(request.copy(targetParentUri = "nested")) {} }
        assertNull(fs.child("nested", "Photos"))
    }

    @Test fun providerCreateReturningSourceAliasNeverOpensSourceForWriting() = runBlocking {
        val fs = FakeDirectoryBackend().apply { fixture(); aliasCreatedFile = true }
        expectFailure { DirectoryMutationEngine(fs).execute(request) {} }
        assertEquals("secret", fs.text("hidden"))
        assertEquals(0, fs.sourceWriteAttempts)
    }

    @Test fun doublingFileCountDoesNotQuadrupleCleanupReadBytes() = runBlocking {
        suspend fun bytesFor(count: Int): Long {
            val fs = FakeDirectoryBackend()
            fs.add("source", "root", "Photos", true)
            repeat(count) { fs.add("file-$it", "source", "file-$it.bin", false, "x".repeat(1024)) }
            assertEquals("FILESYSTEM_DONE", DirectoryMutationEngine(fs).execute(request) {}.phase)
            return fs.bytesRead
        }
        val eightFiles = bytesFor(8)
        val sixteenFiles = bytesFor(16)
        assertTrue("Read bytes must scale linearly: 8=$eightFiles, 16=$sixteenFiles", sixteenFiles <= eightFiles * 2.2)
    }

    @Test fun fileChangedAfterCleanupBeginsRemainsAtSource() = runBlocking {
        val fs = FakeDirectoryBackend().apply { fixture() }
        fs.afterDelete = { fs.items.getValue("hidden").bytes = "externally changed".toByteArray(); fs.afterDelete = null }
        expectFailure { DirectoryMutationEngine(fs).execute(request) {} }
        assertEquals("externally changed", fs.text("hidden"))
        assertNotNull(fs.child("root", "Photos"))
    }

    @Test fun newFileAddedAfterCleanupBeginsIsNeverRecursivelyDeleted() = runBlocking {
        val fs = FakeDirectoryBackend().apply { fixture() }
        fs.afterDelete = { fs.add("external", "source", ".new-file", false, "new bytes"); fs.afterDelete = null }
        expectFailure { DirectoryMutationEngine(fs).execute(request) {} }
        assertEquals("new bytes", fs.text("external"))
        assertNotNull(fs.child("root", "Photos"))
    }

    @Test fun codecRestoresOwnershipAndProgressForActualResume() = runBlocking {
        val fs = FakeDirectoryBackend().apply { fixture(); failWrite = true }
        var journal: DirectoryMutationState? = null
        expectFailure { DirectoryMutationEngine(fs).execute(request) { journal = it } }
        val decoded = DirectoryMutationStateCodec.decode(DirectoryMutationStateCodec.encode(journal!!))
        assertEquals(journal, decoded)
        fs.failWrite = false
        assertEquals("FILESYSTEM_DONE", DirectoryMutationEngine(fs).execute(request, decoded) {}.phase)
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected operation to pause") } catch (_: IOException) { }
    }
}

internal class FakeDirectoryBackend : DirectoryBackend {
    data class Item(var node: DirectoryNode, var parent: String, var bytes: ByteArray = byteArrayOf())
    val items = linkedMapOf<String, Item>()
    var failWrite = false
    var corruptWrites = false
    var failAfterMove = false
    var failAfterRename = false
    var failAfterDelete = false
    var failAfterCreate = false
    var afterWrite: (() -> Unit)? = null
    var aliasCreatedFile = false
    var sourceWriteAttempts = 0
    var bytesRead = 0L
    var afterDelete: (() -> Unit)? = null
    var moves = 0
    private var counter = 0
    fun add(id: String, parent: String, name: String, directory: Boolean, text: String = "", native: Boolean = false) {
        items[id] = Item(DirectoryNode(id, id, name, directory, supportsMove = native, supportsRename = native), parent, text.toByteArray())
    }
    fun fixture(native: Boolean = false) {
        add("source", "root", "Photos", true, native = native)
        add("hidden", "source", ".hidden", false, "secret")
        add("nested", "source", "nested", true)
        add("notes", "nested", "notes.txt", false, "notes")
    }
    fun child(parent: String, name: String) = children(parent).singleOrNull { it.name == name }
    fun text(uri: String) = String(items.getValue(uri).bytes)
    override fun children(parentUri: String) = items.values.filter { it.parent == parentUri }.map { it.node }
    override fun read(uri: String) = object : ByteArrayInputStream(items.getValue(uri).bytes) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
    }
    override fun write(uri: String): ByteArrayOutputStream {
        if (uri == "hidden") sourceWriteAttempts++
        if (failWrite) throw IOException("Quota exceeded")
        return object : ByteArrayOutputStream() {
            override fun close() { items.getValue(uri).bytes = if (corruptWrites) "bad".toByteArray() else toByteArray(); afterWrite?.invoke() }
        }
    }
    override fun create(parentUri: String, name: String, directory: Boolean, mimeType: String): DirectoryNode {
        if (aliasCreatedFile && name == ".hidden") return items.getValue("hidden").node
        val id = "created-${counter++}"
        add(id, parentUri, name, directory)
        if (failAfterCreate) throw IOException("Lost create response")
        return items.getValue(id).node
    }
    override fun delete(uri: String) {
        check(children(uri).isEmpty()) { "Refusing recursive deletion" }
        items.remove(uri)
        afterDelete?.invoke()
        if (failAfterDelete) throw IOException("Lost delete response")
    }
    override fun move(uri: String, sourceParentUri: String, targetParentUri: String): DirectoryNode {
        moves++
        items.getValue(uri).parent = targetParentUri
        if (failAfterMove) throw IOException("Lost provider response")
        return items.getValue(uri).node
    }
    override fun rename(uri: String, name: String): DirectoryNode {
        items.getValue(uri).node = items.getValue(uri).node.copy(name = name)
        if (failAfterRename) throw IOException("Lost rename response")
        return items.getValue(uri).node
    }
}
