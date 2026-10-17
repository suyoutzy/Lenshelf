package com.example.photocategorycamera.data

import android.util.AtomicFile
import com.example.photocategorycamera.storage.operations.DirectoryMutationState
import com.example.photocategorycamera.storage.operations.DirectoryMutationStateCodec
import java.io.File
import java.io.IOException

/** Large manifests stay outside CursorWindow; the small Room row owns the operation lock. */
internal class DirectoryOperationJournal(files: File) {
    private val directory = File(files, "directory-journals")

    fun write(id: String, state: DirectoryMutationState): String {
        val file = journal(id)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建目录操作日志" }
        val bytes = DirectoryMutationStateCodec.encode(state).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
        if (!file.readFully().contentEquals(bytes)) throw IOException("目录操作日志未完整保存，操作已暂停")
        return REFERENCE
    }

    fun read(id: String, value: String): DirectoryMutationState? = when {
        value.isBlank() -> null
        value == REFERENCE -> DirectoryMutationStateCodec.decode(journal(id).readFully().toString(Charsets.UTF_8))
        else -> DirectoryMutationStateCodec.decode(value) // Compatibility with initial inline journals.
    }

    private fun journal(id: String): AtomicFile {
        require(id.matches(Regex("[A-Za-z0-9-]+"))) { "目录操作日志标识无效" }
        return AtomicFile(File(directory, "$id.json"))
    }

    private companion object { const val REFERENCE = "file-journal-v1" }
}
