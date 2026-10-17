package com.example.photocategorycamera.storage.operations

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DirectoryNode(val uri: String, val identity: String, val name: String, val directory: Boolean,
    val mimeType: String = "application/octet-stream", val virtual: Boolean = false, val partial: Boolean = false,
    val supportsMove: Boolean = false, val supportsRename: Boolean = false)

interface DirectoryBackend {
    fun children(parentUri: String): List<DirectoryNode>
    fun read(uri: String): InputStream
    fun write(uri: String): OutputStream
    fun create(parentUri: String, name: String, directory: Boolean, mimeType: String): DirectoryNode
    fun delete(uri: String)
    fun move(uri: String, sourceParentUri: String, targetParentUri: String): DirectoryNode
    fun rename(uri: String, name: String): DirectoryNode
}

data class DirectoryMutationRequest(val kind: String, val sourceParentUri: String, val targetParentUri: String,
    val sourceName: String, val targetName: String)

data class DirectoryManifestEntry(val path: String, val source: DirectoryNode, val sha256: String,
    val targetUri: String? = null, val copied: Boolean = false, val deleted: Boolean = false)

data class DirectoryMutationState(val request: DirectoryMutationRequest, val phase: String,
    val entries: List<DirectoryManifestEntry>, val createIntentPath: String? = null,
    val deleteIntentPath: String? = null, val nativeTargetUri: String? = null)

class DirectoryMutationEngine(private val backend: DirectoryBackend) {
    private val mutex = Mutex()

    /** The caller must durably save every callback before it returns, and run this on an IO dispatcher. */
    suspend fun execute(request: DirectoryMutationRequest, state: DirectoryMutationState? = null,
        persist: suspend (DirectoryMutationState) -> Unit): DirectoryMutationState = mutex.withLock {
        validateRequest(request)
        if (state != null && state.request != request) pause("Journal does not match the requested operation")
        var saved = state ?: run {
            if (find(request.targetParentUri, request.targetName) != null) pause("Destination already exists")
            val source = find(request.sourceParentUri, request.sourceName) ?: pause("Source directory is missing")
            if (!source.directory) pause("Source is not a directory")
            val entries = scan(source)
            if (entries.any { it.source.uri == request.targetParentUri }) pause("Destination is inside the source directory")
            DirectoryMutationState(request, "SCANNED", entries).also { persist(it) }
        }
        suspend fun save(value: DirectoryMutationState) { persist(value); saved = value }

        if (saved.phase == "FILESYSTEM_DONE") {
            verifyTarget(saved)
            if (find(request.sourceParentUri, request.sourceName) != null) pause("Source reappeared after completion")
            return@withLock saved
        }
        if (saved.phase == "SCANNED") {
            verifySource(saved)
            val source = saved.entries.first().source
            val native = if (request.kind == "TRANSFER_CATEGORY") source.supportsMove else source.supportsRename
            save(saved.copy(phase = if (native) "NATIVE_INTENT" else "COPYING"))
        }
        if (saved.phase == "NATIVE_INTENT") {
            val source = find(request.sourceParentUri, request.sourceName)
            val target = find(request.targetParentUri, request.targetName)
            if (source == null && target != null) {
                // IDs may change during native moves. Reconciliation only acknowledges the exact resulting
                // layout after the source disappeared; it never writes to or deletes this discovered target.
                verifyManifest(scan(target), saved.entries, checkSourceIdentity = false)
                save(saved.copy(phase = "VERIFY_NATIVE", nativeTargetUri = target.uri))
            } else if (source != null && target == null) {
                verifySource(saved)
                currentCoroutineContext().ensureActive()
                val returned = if (request.kind == "TRANSFER_CATEGORY") {
                    backend.move(source.uri, request.sourceParentUri, request.targetParentUri)
                } else backend.rename(source.uri, request.targetName)
                // Retain even an unexpected returned URI so it is visible for recovery; never guess a new URI.
                save(saved.copy(phase = "VERIFY_NATIVE", nativeTargetUri = returned.uri))
                if (returned.name != request.targetName) pause("Provider changed the requested directory name")
            } else pause("Native operation has an uncertain outcome; source and destination need reconciliation")
        }
        if (saved.phase == "VERIFY_NATIVE") {
            verifyTarget(saved)
            if (find(request.sourceParentUri, request.sourceName) != null) pause("Native operation left a source directory")
            save(saved.copy(phase = "FILESYSTEM_DONE"))
            return@withLock saved
        }
        if (saved.phase == "COPYING") {
            verifySource(saved)
            for (index in saved.entries.indices) {
                currentCoroutineContext().ensureActive()
                var entry = saved.entries[index]
                val parent = if (entry.path.isEmpty()) request.targetParentUri else {
                    saved.entries.single { it.path == entry.path.substringBeforeLast('/', "") }.targetUri
                        ?: pause("Missing destination parent in journal")
                }
                val name = if (entry.path.isEmpty()) request.targetName else entry.source.name
                val existing = find(parent, name)
                if (entry.targetUri == null) {
                    if (existing != null) pause("Unowned destination exists; refusing to adopt or overwrite it")
                    save(saved.copy(createIntentPath = entry.path))
                    val created = backend.create(parent, name, entry.source.directory, entry.source.mimeType)
                    val aliasesSource = saved.entries.any { it.source.identity == created.identity || it.source.uri == created.uri }
                    val aliasesTarget = saved.entries.any { it.targetUri == created.uri }
                    entry = entry.copy(targetUri = created.uri)
                    save(saved.copy(entries = saved.entries.replaced(index, entry), createIntentPath = null))
                    if (aliasesSource || aliasesTarget) pause("Provider returned an existing document alias for creation")
                    if (created.name != name || created.directory != entry.source.directory) {
                        pause("Provider changed the requested name or document type")
                    }
                } else if (existing?.uri != entry.targetUri || existing.directory != entry.source.directory) {
                    pause("Owned destination is missing, renamed, or replaced")
                }
                if (existing != null && saved.entries.any { it.source.identity == existing.identity || it.source.uri == existing.uri }) {
                    pause("Destination aliases an original source document")
                }
                if (!entry.copied) {
                    if (!entry.source.directory) {
                        backend.read(entry.source.uri).use { input ->
                            backend.write(entry.targetUri!!).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        if (hash(entry.targetUri!!) != entry.sha256) pause("Destination checksum mismatch")
                    }
                    entry = entry.copy(copied = true)
                    save(saved.copy(entries = saved.entries.replaced(index, entry)))
                }
            }
            verifyTarget(saved)
            verifySource(saved)
            save(saved.copy(phase = "CLEANUP"))
        }
        if (saved.phase != "CLEANUP") pause("Unknown journal phase: ${saved.phase}")

        // Every resume checks the complete destination again before continuing destructive cleanup.
        verifyTarget(saved)
        if (saved.deleteIntentPath != null) {
            val index = saved.entries.indexOfFirst { it.path == saved.deleteIntentPath }
            if (index < 0) pause("Invalid deletion journal")
            val entry = saved.entries[index]
            val parent = sourceParent(saved, entry)
            val found = find(parent, entry.source.name)
            if (found == null) save(saved.copy(entries = saved.entries.replaced(index, entry.copy(deleted = true)), deleteIntentPath = null))
            else if (found.identity != entry.source.identity) pause("Source was replaced during cleanup")
        }
        // Revalidate the surviving source once per execution/resume. Individual entries are rechecked
        // immediately before deletion below, avoiding a full-tree reread for every file.
        verifySource(saved)
        for (index in saved.entries.indices.reversed()) {
            val entry = saved.entries[index]
            if (entry.deleted) continue
            currentCoroutineContext().ensureActive()
            save(saved.copy(deleteIntentPath = entry.path))
            verifyBeforeDelete(saved, entry)
            backend.delete(entry.source.uri)
            save(saved.copy(entries = saved.entries.replaced(index, entry.copy(deleted = true)), deleteIntentPath = null))
        }
        verifyTarget(saved)
        if (find(request.sourceParentUri, request.sourceName) != null) pause("Source remains after cleanup")
        save(saved.copy(phase = "FILESYSTEM_DONE"))
        saved
    }

    private fun validateRequest(request: DirectoryMutationRequest) {
        if (request.kind !in setOf("TRANSFER_CATEGORY", "RENAME_CATEGORY", "RENAME_GROUP")) pause("Unknown operation kind")
        validName(request.sourceName); validName(request.targetName)
        if (request.kind == "TRANSFER_CATEGORY" && request.sourceName != request.targetName) pause("Transfer cannot also rename")
        if (request.kind != "TRANSFER_CATEGORY" && request.sourceParentUri != request.targetParentUri) pause("Rename must keep its parent")
        if (request.sourceParentUri == request.targetParentUri && request.sourceName == request.targetName) pause("Source and target are identical")
    }

    private fun find(parent: String, name: String): DirectoryNode? {
        val nodes = backend.children(parent)
        if (nodes.map { it.name }.distinct().size != nodes.size) pause("Ambiguous duplicate document names")
        return nodes.singleOrNull { it.name == name }
    }

    private suspend fun scan(root: DirectoryNode): List<DirectoryManifestEntry> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<DirectoryManifestEntry>()
        suspend fun visit(node: DirectoryNode, path: String) {
            currentCoroutineContext().ensureActive()
            validName(node.name)
            if (node.virtual || node.partial) pause("Virtual or partial documents cannot be safely moved")
            if (!seen.add(node.identity)) pause("Repeated document identity or recursive directory alias")
            result += DirectoryManifestEntry(path, node, if (node.directory) "" else hash(node.uri))
            if (node.directory) {
                val children = backend.children(node.uri)
                if (children.map { it.name }.distinct().size != children.size) pause("Ambiguous child document names")
                for (child in children.sortedBy { it.name }) visit(child, if (path.isEmpty()) child.name else "$path/${child.name}")
            }
        }
        visit(root, "")
        return result
    }

    private suspend fun hash(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        backend.read(uri).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun verifySource(state: DirectoryMutationState) {
        val expected = state.entries.filterNot { it.deleted }
        val root = find(state.request.sourceParentUri, state.request.sourceName)
        if (expected.isEmpty()) { if (root != null) pause("Source reappeared"); return }
        verifyManifest(scan(root ?: pause("Source disappeared")), expected, checkSourceIdentity = true)
    }

    private suspend fun verifyTarget(state: DirectoryMutationState) {
        val root = find(state.request.targetParentUri, state.request.targetName) ?: pause("Destination is missing")
        val owned = state.nativeTargetUri ?: state.entries.first().targetUri ?: pause("No owned destination in journal")
        if (root.uri != owned) pause("Destination identity changed")
        val actual = scan(root)
        verifyManifest(actual, state.entries, checkSourceIdentity = false)
        if (state.nativeTargetUri == null && actual.any { node -> state.entries.single { it.path == node.path }.targetUri != node.source.uri }) {
            pause("Destination entry was replaced")
        }
    }

    private fun verifyManifest(actual: List<DirectoryManifestEntry>, expected: List<DirectoryManifestEntry>, checkSourceIdentity: Boolean) {
        if (actual.map { it.path }.toSet() != expected.map { it.path }.toSet()) pause("Directory contents changed")
        val byPath = expected.associateBy { it.path }
        for (entry in actual) {
            val original = byPath.getValue(entry.path)
            if (entry.source.directory != original.source.directory || entry.sha256 != original.sha256 ||
                (checkSourceIdentity && entry.source.identity != original.source.identity)) pause("Document changed: ${entry.path}")
        }
    }

    private fun sourceParent(state: DirectoryMutationState, entry: DirectoryManifestEntry): String =
        if (entry.path.isEmpty()) state.request.sourceParentUri else state.entries.single { it.path == entry.path.substringBeforeLast('/', "") }.source.uri

    private suspend fun verifyBeforeDelete(state: DirectoryMutationState, entry: DirectoryManifestEntry) {
        val source = find(sourceParent(state, entry), entry.source.name) ?: pause("Source disappeared before deletion")
        if (source.identity != entry.source.identity || source.directory != entry.source.directory || source.virtual || source.partial) {
            pause("Source changed before deletion")
        }
        val targetParent = if (entry.path.isEmpty()) state.request.targetParentUri else {
            state.entries.single { it.path == entry.path.substringBeforeLast('/', "") }.targetUri
                ?: pause("Missing destination parent in journal")
        }
        val targetName = if (entry.path.isEmpty()) state.request.targetName else entry.source.name
        val target = find(targetParent, targetName) ?: pause("Destination disappeared before source deletion")
        if (target.uri != entry.targetUri || target.directory != entry.source.directory || target.virtual || target.partial ||
            target.identity == source.identity) pause("Destination changed before source deletion")
        if (source.directory) {
            // Never ask a provider to recursively delete unknown children, including newly-added hidden files.
            if (backend.children(source.uri).isNotEmpty()) pause("Source directory acquired new contents during cleanup")
        } else {
            if (hash(source.uri) != entry.sha256 || hash(target.uri) != entry.sha256) pause("Document changed before deletion: ${entry.path}")
        }
    }

    private fun validName(name: String) { if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\u0000' in name) pause("Unsafe document name") }
    private fun <T> List<T>.replaced(index: Int, value: T) = toMutableList().also { it[index] = value }.toList()
    private fun pause(message: String): Nothing = throw IOException(message)
}
