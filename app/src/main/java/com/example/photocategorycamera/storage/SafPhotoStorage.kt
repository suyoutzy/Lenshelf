package com.example.photocategorycamera.storage

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.example.photocategorycamera.data.SettingsRepository
import com.example.photocategorycamera.domain.CaptureResult
import com.example.photocategorycamera.domain.CaptureStatus
import com.example.photocategorycamera.domain.BinaryContentVerifier
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.MediaKind
import com.example.photocategorycamera.domain.MediaFileClassifier
import com.example.photocategorycamera.domain.PendingMedia
import com.example.photocategorycamera.domain.PhotoFileNameGenerator
import com.example.photocategorycamera.domain.StorageRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

class SafPhotoStorage(
    private val context: Context,
    private val settings: SettingsRepository,
) : MediaStorage {
    private val resolver get() = context.contentResolver

    override fun hasPersistedAccess(treeUri: Uri): Boolean =
        resolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isReadPermission && permission.isWritePermission
        }

    override suspend fun prepareRoot(treeUri: Uri, categories: List<Category>): Result<StorageRoot> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(hasPersistedAccess(treeUri)) { "目录授权未持久化，请重新选择目录" }
                val root = requireWritableRoot(treeUri)
                ensureNoMedia(root)
                verifyWritable(root)
                categories.filter { it.isActive }.forEach { ensureDirectory(root, it.name) }
                StorageRoot(
                    treeUri = treeUri.toString(),
                    displayName = root.name ?: "已授权目录",
                    authorizedAt = System.currentTimeMillis(),
                )
            }
        }

    override suspend fun listCategoryDirectories(treeUri: Uri): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(hasPersistedAccess(treeUri)) { "目录授权已失效，请在设置中重新选择" }
                val root = requireWritableRoot(treeUri)
                root.listFiles()
                    .filter(DocumentFile::isDirectory)
                    .mapNotNull(DocumentFile::getName)
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
            }
        }

    override suspend fun countCategoryMedia(treeUri: Uri, categoryNames: List<String>): Result<Map<String, Int>> =
        withContext(Dispatchers.IO) {
            try {
                require(hasPersistedAccess(treeUri)) { "目录授权已失效" }
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                fun children(id: String) = resolver.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id),
                    columns, null, null, null,
                ) ?: throw IOException("无法读取分类文件夹")
                val directoryIds = mutableMapOf<String, String>()
                children(DocumentsContract.getTreeDocumentId(treeUri)).use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            directoryIds[cursor.getString(1)] = cursor.getString(0)
                        }
                    }
                }
                val counts = categoryNames.associateWith { name ->
                    coroutineContext.ensureActive()
                    val id = directoryIds[name]
                    if (id == null) 0 else children(id).use { cursor ->
                        var count = 0
                        while (cursor.moveToNext()) {
                            coroutineContext.ensureActive()
                            if (MediaFileClassifier.isMedia(cursor.getString(1).orEmpty(), cursor.getString(2))) count++
                        }
                        count
                    }
                }
                Result.success(counts)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    override suspend fun ensureCategory(treeUri: Uri, categoryName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = requireWritableRoot(treeUri)
                ensureNoMedia(root)
                ensureDirectory(root, categoryName)
                Unit
            }
        }

    override suspend fun categoryDirectoryUri(treeUri: Uri, categoryName: String): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(hasPersistedAccess(treeUri)) { "目录授权已失效，请在设置中重新选择" }
                val root = requireWritableRoot(treeUri)
                ensureDirectory(root, categoryName).uri
            }
        }

    override suspend fun renameCategoryDirectory(treeUri: Uri, oldName: String, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(hasPersistedAccess(treeUri)) { "目录授权已失效，请在设置中重新选择" }
                val root = requireWritableRoot(treeUri)
                val directory = root.findFile(oldName)
                    ?: throw IOException("原分类文件夹不存在，未修改分类名称")
                if (!directory.isDirectory) throw IOException("“$oldName”不是分类目录，未执行重命名")
                val conflicting = root.listFiles().firstOrNull { file ->
                    file.uri != directory.uri && file.name.equals(newName, ignoreCase = true)
                }
                if (conflicting != null) throw IOException("同名文件夹已存在")
                if (oldName == newName) return@runCatching Unit
                if (!directory.renameTo(newName)) throw IOException("分类文件夹重命名失败")
            }
        }

    override suspend fun deleteCategoryDirectoryWithContents(treeUri: Uri, categoryName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(hasPersistedAccess(treeUri)) { "目录授权已失效，请在设置中重新选择" }
                val root = requireWritableRoot(treeUri)
                val directory = root.findFile(categoryName) ?: return@runCatching Unit
                if (!directory.isDirectory) throw IOException("“$categoryName”不是分类目录，未执行删除")
                if (!directory.delete()) throw IOException("分类文件夹及其中内容删除失败")
            }
        }

    override suspend fun storeTempMedia(
        treeUri: Uri,
        tempFile: File,
        category: Category,
        mediaKind: MediaKind,
    ): CaptureResult = withContext(Dispatchers.IO) {
        val validation = validateMedia(tempFile, mediaKind)
        if (!validation.valid) {
            return@withContext CaptureResult(
                categoryId = category.id,
                status = CaptureStatus.FAILED,
                error = validation.error ?: "临时媒体文件无效，未写入目标目录",
            )
        }

        val existingPending = settings.settings.first().pendingCapture
        if (existingPending != null && existingPending.tempPath != tempFile.absolutePath) {
            return@withContext CaptureResult(
                categoryId = category.id,
                status = CaptureStatus.FAILED,
                error = "仍有媒体文件等待恢复，请先完成重试",
            )
        }
        val pending = PendingMedia(tempFile.absolutePath, category.id, category.name, mediaKind)
        settings.setPendingCapture(pending)
        copyPending(treeUri, pending)
    }

    override suspend fun reserveTarget(treeUri: Uri, category: Category, mediaKind: MediaKind): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(hasPersistedAccess(treeUri)) { "目录授权已失效，请在设置中重新选择" }
                val root = requireWritableRoot(treeUri)
                ensureNoMedia(root)
                val directory = ensureDirectory(root, category.name)
                val fileName = nextAvailableName(directory, mediaKind)
                val mimeType = if (mediaKind == MediaKind.VIDEO) "video/mp4" else "image/jpeg"
                directory.createFile(mimeType, fileName)?.uri ?: throw IOException("无法在分类目录中创建媒体文件")
            }
        }

    override suspend fun storeTempMediaToTarget(
        targetUri: Uri,
        tempFile: File,
        category: Category,
        mediaKind: MediaKind,
    ): CaptureResult = withContext(Dispatchers.IO) {
        val validation = validateMedia(tempFile, mediaKind)
        if (!validation.valid) {
            return@withContext CaptureResult(
                categoryId = category.id,
                status = CaptureStatus.FAILED,
                error = validation.error ?: "临时媒体文件无效，未写入目标目录",
            )
        }
        val target = DocumentFile.fromSingleUri(context, targetUri)
            ?: return@withContext CaptureResult(category.id, status = CaptureStatus.RETRY_AVAILABLE, error = "无法访问待写入文件")
        if (!target.exists() || !target.isFile) {
            return@withContext CaptureResult(
                category.id,
                status = CaptureStatus.RETRY_AVAILABLE,
                error = "待写入文件已不存在",
            )
        }
        try {
            if (target.exists() && target.isFile) {
                val alreadyComplete = runCatching {
                    tempFile.inputStream().buffered().use { source ->
                        resolver.openInputStream(targetUri)?.buffered()?.use { destination ->
                            BinaryContentVerifier.sameContent(source, destination)
                        } ?: false
                    }
                }.getOrDefault(false)
                if (alreadyComplete) {
                    tempFile.delete()
                    return@withContext CaptureResult(category.id, targetUri.toString(), CaptureStatus.SAVED, warning = validation.warning)
                }
            }
            resolver.openOutputStream(targetUri, "w")?.use { output ->
                tempFile.inputStream().buffered().use { input -> input.copyTo(output) }
                output.flush()
            } ?: throw IOException("无法打开目标媒体输出流")
            verifyCopiedContent(tempFile, target)
            tempFile.delete()
            CaptureResult(category.id, targetUri.toString(), CaptureStatus.SAVED, warning = validation.warning)
        } catch (error: Exception) {
            CaptureResult(category.id, targetUri.toString(), CaptureStatus.RETRY_AVAILABLE,
                error = error.message ?: "媒体保存失败，可稍后重试")
        }
    }

    override suspend fun retry(treeUri: Uri, pending: PendingMedia): CaptureResult =
        withContext(Dispatchers.IO) { copyPending(treeUri, pending) }

    private suspend fun copyPending(treeUri: Uri, pending: PendingMedia): CaptureResult {
        val tempFile = File(pending.tempPath)
        val validation = validateMedia(tempFile, pending.mediaKind)
        if (!validation.valid) {
            settings.clearPendingCapture()
            return CaptureResult(
                categoryId = pending.categoryId,
                status = CaptureStatus.FAILED,
                error = validation.error ?: "待恢复的临时媒体不存在或已损坏",
            )
        }

        var target: DocumentFile? = null
        return try {
            require(hasPersistedAccess(treeUri)) { "目录授权已失效，请在设置中重新选择" }
            val root = requireWritableRoot(treeUri)
            ensureNoMedia(root)
            val categoryDirectory = ensureDirectory(root, pending.categoryName)
            val fileName = nextAvailableName(categoryDirectory, pending.mediaKind)
            val mimeType = if (pending.mediaKind == MediaKind.VIDEO) "video/mp4" else "image/jpeg"
            target = categoryDirectory.createFile(mimeType, fileName)
                ?: throw IOException("无法在分类目录中创建媒体文件")

            resolver.openOutputStream(target.uri, "w")?.use { output ->
                tempFile.inputStream().buffered().use { input -> input.copyTo(output) }
                output.flush()
            } ?: throw IOException("无法打开目标媒体输出流")

            verifyCopiedContent(tempFile, target)

            tempFile.delete()
            settings.clearPendingCapture()
            CaptureResult(
                categoryId = pending.categoryId,
                fileUri = target.uri.toString(),
                status = CaptureStatus.SAVED,
                warning = validation.warning,
            )
        } catch (error: Exception) {
            target?.delete()
            CaptureResult(
                categoryId = pending.categoryId,
                status = CaptureStatus.RETRY_AVAILABLE,
                error = error.message ?: "媒体保存失败，可稍后重试",
            )
        }
    }

    private fun requireWritableRoot(treeUri: Uri): DocumentFile {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("无法访问所选根目录")
        if (!root.exists() || !root.isDirectory) throw IOException("根目录已被移动或删除")
        if (!root.canWrite()) throw IOException("根目录当前不可写")
        return root
    }

    private fun ensureNoMedia(root: DocumentFile) {
        if (root.findFile(MediaStore.MEDIA_IGNORE_FILENAME) != null) return
        root.createFile("application/octet-stream", MediaStore.MEDIA_IGNORE_FILENAME)
            ?: throw IOException("无法在根目录创建 .nomedia")
        if (root.findFile(MediaStore.MEDIA_IGNORE_FILENAME) == null) {
            throw IOException("存储提供程序未保留 .nomedia 文件名")
        }
    }

    private fun verifyWritable(root: DocumentFile) {
        val probeName = ".write_probe_${System.currentTimeMillis()}"
        val probe = root.createFile("application/octet-stream", probeName)
            ?: throw IOException("根目录写入测试失败")
        try {
            resolver.openOutputStream(probe.uri, "w")?.use {
                it.write(byteArrayOf(1))
                it.flush()
            }
                ?: throw IOException("根目录写入测试失败")
            val probeByte = resolver.openInputStream(probe.uri)?.use { it.read() }
                ?: throw IOException("无法读回根目录写入测试文件")
            if (probeByte != 1) throw IOException("根目录写入内容校验失败")
        } finally {
            probe.delete()
        }
    }

    private fun ensureDirectory(root: DocumentFile, name: String): DocumentFile {
        val existing = root.findFile(name)
        if (existing != null) {
            if (!existing.isDirectory) throw IOException("“$name”已存在，但不是目录")
            if (!existing.canWrite()) throw IOException("分类目录“$name”不可写")
            return existing
        }
        return root.createDirectory(name) ?: throw IOException("无法创建分类目录“$name”")
    }

    private fun nextAvailableName(directory: DocumentFile, mediaKind: MediaKind): String {
        val base = PhotoFileNameGenerator.baseName(mediaKind)
        for (suffix in 0..9_999) {
            val candidate = PhotoFileNameGenerator.candidate(base, suffix)
            if (directory.findFile(candidate) == null) return candidate
        }
        throw IOException("短时间内产生了过多同名媒体文件")
    }

    private fun validateMedia(file: File, mediaKind: MediaKind): MediaValidation = when (mediaKind) {
        MediaKind.PHOTO -> MediaValidation(
            valid = isReadableJpeg(file),
            error = "待保存的JPEG不存在或无法解码",
        )
        MediaKind.MOTION_PHOTO -> validateMotionPhoto(file)
        MediaKind.VIDEO -> validateVideo(file)
    }

    private fun validateMotionPhoto(file: File): MediaValidation {
        if (!isReadableJpeg(file)) return MediaValidation(false, error = "Live图静态照片不存在或无法解码")
        return runCatching {
            val prefixSize = minOf(file.length(), 256L * 1024L).toInt()
            val prefix = ByteArray(prefixSize)
            RandomAccessFile(file, "r").use { input -> input.readFully(prefix) }
            val xmp = String(prefix, StandardCharsets.ISO_8859_1)
            val videoLength = Regex("MicroVideoOffset=\"(\\d+)\"").find(xmp)
                ?.groupValues?.get(1)?.toLongOrNull()
                ?: return MediaValidation(false, error = "Live图缺少动态片段长度")
            if (videoLength <= 8L || videoLength >= file.length()) {
                return MediaValidation(false, error = "Live图动态片段长度无效")
            }
            val videoFile = File.createTempFile("verify_motion_", ".mp4", context.cacheDir)
            try {
                RandomAccessFile(file, "r").use { input ->
                    input.seek(file.length() - videoLength)
                    videoFile.outputStream().buffered().use { output ->
                        val bytes = ByteArray(64 * 1024)
                        var remaining = videoLength
                        while (remaining > 0L) {
                            val count = input.read(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt())
                            if (count < 0) throw IOException("Live图动态部分提前结束")
                            output.write(bytes, 0, count)
                            remaining -= count
                        }
                    }
                }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(videoFile.absolutePath)
                    val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
                    } ?: return MediaValidation(false, error = "Live图动态部分没有视频轨")
                    val trackFormat = extractor.getTrackFormat(videoTrack)
                    extractor.selectTrack(videoTrack)
                    val sampleCapacity = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    } else 1024 * 1024
                    val sample = ByteBuffer.allocate(sampleCapacity.coerceIn(1024 * 1024, 8 * 1024 * 1024))
                    if (extractor.readSampleData(sample, 0) < 0 || extractor.sampleTime < 0L) {
                        return MediaValidation(false, error = "Live图动态部分没有可解码视频帧")
                    }
                } finally {
                    extractor.release()
                }
            } finally {
                videoFile.delete()
            }
            MediaValidation(valid = true)
        }.getOrElse { error ->
            MediaValidation(false, error = "Live图动态部分无法解析：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun validateVideo(file: File): MediaValidation {
        if (!file.isFile || file.length() <= 0L) {
            return MediaValidation(false, error = "待保存的视频不存在或为空")
        }
        return runCatching {
            val extractor = MediaExtractor()
            var hasVideoTrack = false
            var hasAudioTrack = false
            try {
                extractor.setDataSource(file.absolutePath)
                repeat(extractor.trackCount) { index ->
                    val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("video/")) hasVideoTrack = true
                    if (mime.startsWith("audio/")) hasAudioTrack = true
                }
            } finally {
                extractor.release()
            }
            val retriever = MediaMetadataRetriever()
            val durationMs = try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
            if (!hasVideoTrack || durationMs <= 0L) {
                MediaValidation(false, error = "视频没有可解析的画面轨或有效时长")
            } else if (!hasAudioTrack) {
                MediaValidation(false, error = "视频没有可解析的声音轨，未写入目标目录")
            } else {
                MediaValidation(valid = true)
            }
        }.getOrElse {
            MediaValidation(false, error = "视频文件无法解析")
        }
    }

    private fun isReadableJpeg(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0 && options.outMimeType == "image/jpeg"
    }

    private fun verifyCopiedContent(source: File, target: DocumentFile) {
        source.inputStream().buffered().use { sourceStream ->
            resolver.openInputStream(target.uri)?.buffered()?.use { targetStream ->
                if (!BinaryContentVerifier.sameContent(sourceStream, targetStream)) {
                    throw IOException("目标媒体内容与拍摄文件不一致")
                }
            } ?: throw IOException("目标媒体写入后无法读回")
        }
    }

    private data class MediaValidation(
        val valid: Boolean,
        val error: String? = null,
        val warning: String? = null,
    )
}
