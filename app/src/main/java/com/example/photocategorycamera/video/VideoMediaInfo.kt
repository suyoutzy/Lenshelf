package com.example.photocategorycamera.video

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

internal class InvalidVideoSourceException(message: String, cause: Throwable? = null) : IOException(message,cause)

internal data class VideoMediaInfo(
    val width: Int, val height: Int, val rotation: Int, val durationMs: Long,
    val bitrate: Int, val frameRate: Float, val videoMime: String,
    val audioMime: String, val audioDigest: ByteArray, val audioStartUs: Long, val videoStartUs: Long,
) {
    val displayWidth: Int get() = if (rotation%180==0) width else height
    val displayHeight: Int get() = if (rotation%180==0) height else width

    companion object {
        fun read(file: File): VideoMediaInfo {
            check(file.isFile && file.length()>8) { "视频原件不存在或不完整" }
            val extractor=MediaExtractor()
            val retriever=MediaMetadataRetriever()
            try {
                extractor.setDataSource(file.path);retriever.setDataSource(file.path)
                val video=(0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true
                } ?: error("视频轨道缺失")
                val audio=(0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true
                } ?: error("声音轨道缺失")
                val format=extractor.getTrackFormat(video)
                val audioFormat=extractor.getTrackFormat(audio)
                val width=format.getInteger(MediaFormat.KEY_WIDTH);val height=format.getInteger(MediaFormat.KEY_HEIGHT)
                val rotation=((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0)%360+360)%360
                val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                check(width>0 && height>0 && duration>0 && rotation%90==0) { "视频尺寸、方向或时长无效" }
                val bitrate=(if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE).toLong() else
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                        ?: (file.length()*8000/duration)).coerceIn(1,Int.MAX_VALUE.toLong()).toInt()
                val rate=if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat() ?: 0f else 0f
                extractor.selectTrack(video)
                val videoStart=extractor.sampleTime
                check(videoStart>=0) { "视频未生成有效画面" }
                extractor.unselectTrack(video);extractor.selectTrack(audio);extractor.seekTo(0,MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val audioStart=extractor.sampleTime
                check(audioStart>=0) { "视频未生成有效声音" }
                val digest=MessageDigest.getInstance("SHA-256")
                val buffer=ByteBuffer.allocate(1024*1024)
                while (true) {
                    buffer.clear();val size=extractor.readSampleData(buffer,0)
                    if(size<0) break
                    buffer.position(0);buffer.limit(size);digest.update(buffer)
                    if(!extractor.advance()) break
                }
                return VideoMediaInfo(width,height,rotation,duration,bitrate,rate,
                    format.getString(MediaFormat.KEY_MIME)!!,audioFormat.getString(MediaFormat.KEY_MIME)!!,
                    digest.digest(),audioStart,videoStart)
            } finally { extractor.release();retriever.release() }
        }
    }
}
