package com.example.photocategorycamera.storage.operations

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Base64

/** Versioned JSON envelope; binary payload avoids Android's unavailable org.json implementation in JVM tests. */
object DirectoryMutationStateCodec {
    fun encode(state: DirectoryMutationState): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            with(out) {
                text(state.request.kind); text(state.request.sourceParentUri); text(state.request.targetParentUri)
                text(state.request.sourceName); text(state.request.targetName); text(state.phase)
                nullable(state.createIntentPath); nullable(state.deleteIntentPath); nullable(state.nativeTargetUri)
                writeInt(state.entries.size)
                state.entries.forEach { entry ->
                    text(entry.path)
                    with(entry.source) {
                        text(uri); text(identity); text(name); writeBoolean(directory); text(mimeType)
                        writeBoolean(virtual); writeBoolean(partial); writeBoolean(supportsMove); writeBoolean(supportsRename)
                    }
                    text(entry.sha256); nullable(entry.targetUri); writeBoolean(entry.copied); writeBoolean(entry.deleted)
                }
            }
        }
        return "{\"version\":1,\"payload\":\"${Base64.getEncoder().encodeToString(bytes.toByteArray())}\"}"
    }

    fun decode(json: String): DirectoryMutationState {
        val match = Regex("""\{"version":1,"payload":"([A-Za-z0-9+/=]*)"\}""").matchEntire(json)
            ?: throw IOException("Unsupported or malformed directory journal")
        try {
            DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(match.groupValues[1]))).use { input ->
                with(input) {
                    val request = DirectoryMutationRequest(text(), text(), text(), text(), text())
                    val phase = text()
                    val create = nullable(); val delete = nullable(); val native = nullable()
                    val count = readInt()
                    if (count !in 1..100_000) throw IOException("Invalid directory manifest size")
                    val entries = List(count) {
                        val path = text()
                        val node = DirectoryNode(text(), text(), text(), readBoolean(), text(), readBoolean(), readBoolean(), readBoolean(), readBoolean())
                        DirectoryManifestEntry(path, node, text(), nullable(), readBoolean(), readBoolean())
                    }
                    if (read() != -1 || entries.first().path.isNotEmpty() || entries.map { it.path }.distinct().size != entries.size) {
                        throw IOException("Invalid directory manifest")
                    }
                    return DirectoryMutationState(request, phase, entries, create, delete, native)
                }
            }
        } catch (error: IllegalArgumentException) { throw IOException("Malformed directory journal", error) }
    }

    private fun DataOutputStream.text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); writeInt(bytes.size); write(bytes) }
    private fun DataOutputStream.nullable(value: String?) { writeBoolean(value != null); if (value != null) text(value) }
    private fun DataInputStream.text(): String {
        val size = readInt()
        if (size < 0 || size > 4_194_304 || size > available()) throw IOException("Invalid journal string size")
        val bytes = ByteArray(size); readFully(bytes); return bytes.toString(Charsets.UTF_8)
    }
    private fun DataInputStream.nullable(): String? = if (readBoolean()) text() else null
}
