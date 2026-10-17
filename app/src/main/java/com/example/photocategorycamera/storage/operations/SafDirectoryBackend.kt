package com.example.photocategorycamera.storage.operations

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Strict SAF queries: unavailable/partial results are errors, never an empty directory. */
class SafDirectoryBackend(private val resolver: ContentResolver) : DirectoryBackend {
    private val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS)

    override fun children(parentUri: String): List<DirectoryNode> {
        val parent = documentUri(parentUri)
        val query = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val cursor = resolver.query(query, projection, null, null, null)
            ?: throw IOException("Provider returned no directory listing")
        return cursor.use {
            checkComplete(it)
            val result = buildList { while (it.moveToNext()) add(node(it, parent)) }
            checkComplete(it)
            result
        }
    }

    override fun read(uri: String): InputStream = resolver.openInputStream(documentUri(uri))
        ?: throw IOException("Provider did not open source for reading")

    override fun write(uri: String): OutputStream = resolver.openOutputStream(documentUri(uri), "wt")
        ?: throw IOException("Provider did not open destination for writing")

    override fun create(parentUri: String, name: String, directory: Boolean, mimeType: String): DirectoryNode {
        val result = DocumentsContract.createDocument(resolver, documentUri(parentUri),
            if (directory) Document.MIME_TYPE_DIR else mimeType, name)
            ?: throw IOException("Provider did not return the created document URI")
        return inspect(result)
    }

    override fun delete(uri: String) {
        if (!DocumentsContract.deleteDocument(resolver, documentUri(uri))) throw IOException("Provider did not delete document")
    }

    override fun move(uri: String, sourceParentUri: String, targetParentUri: String): DirectoryNode {
        val result = DocumentsContract.moveDocument(resolver, documentUri(uri), documentUri(sourceParentUri), documentUri(targetParentUri))
            ?: throw IOException("Native move outcome is unknown")
        return inspect(result)
    }

    override fun rename(uri: String, name: String): DirectoryNode {
        val result = DocumentsContract.renameDocument(resolver, documentUri(uri), name)
            ?: throw IOException("Native rename outcome is unknown")
        return inspect(result)
    }

    private fun inspect(uri: Uri): DirectoryNode {
        val cursor = resolver.query(uri, projection, null, null, null) ?: throw IOException("Provider returned no document metadata")
        return cursor.use {
            checkComplete(it)
            if (!it.moveToFirst()) throw IOException("Returned document is missing")
            val result = node(it, uri).copy(uri = uri.toString())
            if (it.moveToNext()) throw IOException("Provider returned ambiguous document metadata")
            result
        }
    }

    private fun node(cursor: Cursor, tree: Uri): DirectoryNode {
        fun required(column: String): String {
            val index = cursor.getColumnIndexOrThrow(column)
            if (cursor.isNull(index)) throw IOException("Provider omitted $column")
            return cursor.getString(index)
        }
        val id = required(Document.COLUMN_DOCUMENT_ID)
        val name = required(Document.COLUMN_DISPLAY_NAME)
        val mime = required(Document.COLUMN_MIME_TYPE)
        val flagsIndex = cursor.getColumnIndexOrThrow(Document.COLUMN_FLAGS)
        if (cursor.isNull(flagsIndex)) throw IOException("Provider omitted document flags")
        val flags = cursor.getLong(flagsIndex)
        fun has(flag: Int) = flags and flag.toLong() != 0L
        return DirectoryNode(
            uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
            identity = "${tree.authority}:$id", name = name, directory = mime == Document.MIME_TYPE_DIR,
            mimeType = mime, virtual = has(Document.FLAG_VIRTUAL_DOCUMENT), partial = has(Document.FLAG_PARTIAL),
            supportsMove = has(Document.FLAG_SUPPORTS_MOVE), supportsRename = has(Document.FLAG_SUPPORTS_RENAME),
        )
    }

    private fun checkComplete(cursor: Cursor) {
        if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
            cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR)) throw IOException("Directory listing is incomplete or unavailable")
    }

    private fun documentUri(value: String): Uri {
        val uri = Uri.parse(value)
        return if ("document" in uri.pathSegments) uri else
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    }
}
