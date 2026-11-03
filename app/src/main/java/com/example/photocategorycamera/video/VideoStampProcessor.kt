package com.example.photocategorycamera.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.*
import com.example.photocategorycamera.domain.CaptureSpaceBudget
import com.example.photocategorycamera.domain.MediaStampRecipe
import com.example.photocategorycamera.photo.MediaStampRenderer
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.abs

fun interface VideoStampWriter {
    suspend fun writeResult(source: File, recipe: MediaStampRecipe, output: File, progress: (Int) -> Unit)
}

@SuppressLint("UnsafeOptInUsageError")
class VideoStampProcessor(context: Context) : VideoStampWriter {
    private val context=context.applicationContext

    override suspend fun writeResult(source: File, recipe: MediaStampRecipe, output: File, progress: (Int) -> Unit) {
        require(source.canonicalFile!=output.canonicalFile && recipe.hasContent)
        val info=withContext(Dispatchers.IO) {
            try { VideoMediaInfo.read(source) }
            catch(error: Exception) { throw InvalidVideoSourceException("视频原件无法完整读取",error) }
        }
        check(info.audioMime==MimeTypes.AUDIO_AAC) { "原视频音轨暂不支持无损保留" }
        val free=context.filesDir.usableSpace
        val safety=CaptureSpaceBudget.safetyMargin(free)
        val estimate=maxOf(source.length(),info.bitrate.toLong()*info.durationMs/8000)*5/4+1024*1024
        check(free-safety>=estimate*2) { "空间不足以生成并校验视频，原片已保留" }
        val part=File(output.path+".part")
        output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        part.delete()
        val overlay=Bitmap.createBitmap(info.displayWidth,info.displayHeight,Bitmap.Config.ARGB_8888)
        try {
            MediaStampRenderer.draw(Canvas(overlay),overlay.width.toFloat(),overlay.height.toFloat(),recipe.text)
            progress(0)
            export(source,part,overlay,info.bitrate,safety,progress)
            withContext(Dispatchers.IO) {
                val result=VideoMediaInfo.read(part)
                check(result.videoMime==MimeTypes.VIDEO_H264) { "视频编码与要求不一致" }
                check(result.displayWidth==info.displayWidth && result.displayHeight==info.displayHeight) { "视频导出改变了画面尺寸" }
                check(abs(result.durationMs-info.durationMs)<=100) { "视频导出时长不完整" }
                check(result.audioMime==info.audioMime && result.audioDigest.contentEquals(info.audioDigest)) { "视频音轨校验失败" }
                check(abs((result.audioStartUs-result.videoStartUs)-(info.audioStartUs-info.videoStartUs))<=50_000) { "视频音画起点校验失败" }
                if (info.frameRate>0 && result.frameRate>0) check(abs(info.frameRate-result.frameRate)<=.5f) { "视频导出改变了帧率" }
                RandomAccessFile(part,"rw").use { it.fd.sync() }
                check(part.renameTo(output)) { "无法完成视频水印写入" }
            }
            progress(100)
        } finally { overlay.recycle();part.delete() }
    }

    private suspend fun export(source: File, output: File, overlay: Bitmap, bitrate: Int, safety: Long, progress: (Int) -> Unit) =
        withContext(Dispatchers.Main.immediate) {
            val completed=CompletableDeferred<Unit>()
            val encoder=DefaultEncoderFactory.Builder(context).setEnableFallback(false)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build()).build()
            val transformer=Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264).setEncoderFactory(encoder)
                .setUsePlatformDiagnostics(false)
                .addListener(object: Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { completed.complete(Unit) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        completed.completeExceptionally(exportException)
                    }
                }).build()
            val effect=OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(overlay)))
            val item=EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                .setEffects(Effects(emptyList(),listOf(effect))).build()
            var monitor: Job?=null
            try {
                transformer.start(item,output.path)
                monitor=launch {
                    val holder=ProgressHolder()
                    while (isActive) {
                        if (transformer.getProgress(holder)==Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress.coerceIn(0,99))
                        if (withContext(Dispatchers.IO) { context.filesDir.usableSpace<safety }) {
                            completed.completeExceptionally(IOException("存储空间不足，视频原片已保留"))
                            break
                        }
                        delay(250)
                    }
                }
                completed.await()
            } finally {
                // cancel() blocks for internal codec release. Recording admission waits for this finally.
                withContext(NonCancellable) { monitor?.cancelAndJoin();transformer.cancel() }
            }
        }
}
