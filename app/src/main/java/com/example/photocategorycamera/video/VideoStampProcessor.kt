package com.example.photocategorycamera.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
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
import java.nio.ByteBuffer
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
        val free=context.filesDir.usableSpace
        val safety=CaptureSpaceBudget.safetyMargin(free)
        val estimate=maxOf(source.length(),info.bitrate.toLong()*info.durationMs/8000)*5/4+1024*1024
        check(free-safety>=estimate*2) { "空间不足以生成并校验视频，原片已保留" }
        val part=File(output.path+".part")
        val aligned=File(output.path+".aligned.part")
        output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        part.delete()
        aligned.delete()
        val overlay=Bitmap.createBitmap(info.displayWidth,info.displayHeight,Bitmap.Config.ARGB_8888)
        try {
            MediaStampRenderer.draw(Canvas(overlay),overlay.width.toFloat(),overlay.height.toFloat(),recipe.text)
            progress(0)
            export(source,part,overlay,info.bitrate,safety,progress)
            withContext(Dispatchers.IO) {
                val exported=VideoMediaInfo.read(part)
                // Remuxing shifts every video timestamp equally; some extractors recalculate
                // the average frame rate from the new track duration. Check before that shift.
                if (info.frameRate>0 && exported.frameRate>0) check(abs(info.frameRate-exported.frameRate)<=.5f) { "视频导出改变了帧率" }
                val sourceOffset=info.audioStartUs-info.videoStartUs
                val outputOffset=exported.audioStartUs-exported.videoStartUs
                val candidate = if (abs(outputOffset-sourceOffset)>50_000) {
                    alignTrackStarts(part, aligned, exported, info)
                    aligned
                } else part
                val result=if(candidate==part) exported else VideoMediaInfo.read(candidate)
                check(result.videoMime==MimeTypes.VIDEO_H264) { "视频编码与要求不一致" }
                check(result.displayWidth==info.displayWidth && result.displayHeight==info.displayHeight) { "视频导出改变了画面尺寸" }
                check(abs(result.durationMs-info.durationMs)<=100) { "视频导出时长不完整" }
                check(result.audioMime==info.audioMime && result.audioDigest.contentEquals(info.audioDigest)) { "视频音轨校验失败" }
                check(abs((result.audioStartUs-result.videoStartUs)-(info.audioStartUs-info.videoStartUs))<=50_000) { "视频音画起点校验失败" }
                RandomAccessFile(candidate,"rw").use { it.fd.sync() }
                check(candidate.renameTo(output)) { "无法完成视频水印写入" }
            }
            progress(100)
        } finally { overlay.recycle();part.delete();aligned.delete() }
    }

    /** Restore the original relative track start after an export that rebased tracks separately. */
    private suspend fun alignTrackStarts(source: File, target: File, exported: VideoMediaInfo, original: VideoMediaInfo) {
        val extractor=MediaExtractor()
        var muxer: MediaMuxer?=null
        try {
            extractor.setDataSource(source.path)
            val tracks=(0 until extractor.trackCount).filter {
                val mime=extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("audio/") || mime.startsWith("video/")
            }
            check(tracks.size==2) { "视频轨道无法校准" }
            val writer=MediaMuxer(target.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer=writer
            writer.setOrientationHint(exported.rotation)
            val trackMap=tracks.associateWith { writer.addTrack(extractor.getTrackFormat(it)) }
            val base=minOf(original.audioStartUs,original.videoStartUs)
            val audioDelta=original.audioStartUs-base-exported.audioStartUs
            val videoDelta=original.videoStartUs-base-exported.videoStartUs
            tracks.forEach(extractor::selectTrack)
            writer.start()
            var buffer=ByteBuffer.allocateDirect(1024*1024)
            val info=MediaCodec.BufferInfo()
            while (extractor.sampleTrackIndex>=0) {
                currentCoroutineContext().ensureActive()
                val track=extractor.sampleTrackIndex
                val size=extractor.sampleSize
                check(size in 0..(64L*1024*1024)) { "视频数据块大小无效" }
                if (size>buffer.capacity()) buffer=ByteBuffer.allocateDirect(size.toInt())
                buffer.clear()
                val count=extractor.readSampleData(buffer,0)
                check(count>=0) { "视频轨道读取失败" }
                val isAudio=extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
                val timestamp=extractor.sampleTime+(if(isAudio) audioDelta else videoDelta)
                check(timestamp>=0) { "视频轨道时间无效" }
                val flags=if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0,count,timestamp,flags)
                writer.writeSampleData(checkNotNull(trackMap[track]),buffer,info)
                extractor.advance()
            }
            writer.stop()
        } finally { muxer?.release();extractor.release() }
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
