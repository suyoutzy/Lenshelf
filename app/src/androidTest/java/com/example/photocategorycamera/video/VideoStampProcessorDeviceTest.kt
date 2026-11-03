package com.example.photocategorycamera.video

import android.content.Context
import android.graphics.Color
import android.media.*
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.example.photocategorycamera.domain.MediaStampRecipe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sin

class VideoStampProcessorDeviceTest {
    @Test fun preservesAudioTimingForRealCameraRecording() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val source=File.createTempFile("camera-offset-", ".mp4", context.cacheDir)
        val output=File(source.path+"-stamped.mp4")
        try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
                .open("camera-amr-offset.mp4").use { input -> source.outputStream().use(input::copyTo) }
            val before=VideoMediaInfo.read(source)
            VideoStampProcessor(context).writeResult(source,MediaStampRecipe("2026-11-03", "现场"),output) {}
            val after=VideoMediaInfo.read(output)
            assertArrayEquals(before.audioDigest,after.audioDigest)
            assertTrue(kotlin.math.abs((after.videoStartUs-after.audioStartUs)-(before.videoStartUs-before.audioStartUs))<=50_000)
        } finally { source.delete();output.delete();File(output.path+".part").delete() }
    }
    @Test fun exportsCameraAmrAudioWithoutLosingItsSamples() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("video-amr-source-", ".mp4", context.cacheDir)
        val output = File(source.path + "-stamped.mp4")
        try {
            VideoStampFixture.write(source, audioMime = "audio/3gpp", videoOffsetUs = 72_000)
            val audio = audioHash(source, "audio/3gpp")
            val timing = VideoMediaInfo.read(source)
            assertTrue(timing.videoStartUs - timing.audioStartUs > 50_000)
            VideoStampProcessor(context).writeResult(source, MediaStampRecipe("2026-11-03", "现场"), output) {}
            assertArrayEquals(audio, audioHash(output, "audio/3gpp"))
            val result = VideoMediaInfo.read(output)
            assertTrue(kotlin.math.abs((result.videoStartUs-result.audioStartUs)-(timing.videoStartUs-timing.audioStartUs)) <= 50_000)
        } finally { source.delete(); output.delete(); File(output.path + ".part").delete() }
    }
    @Test fun exportsUprightWatermarksAtEveryRotationWithoutChangingAudioOrSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (rotation in listOf(0,90,180,270)) {
            val source = File.createTempFile("video-stamp-source-", ".mp4", context.cacheDir)
            val output = File(source.path + "-stamped.mp4")
            try {
                VideoStampFixture.write(source,rotation)
                val sourceHash = source.readBytes().let { MessageDigest.getInstance("SHA-256").digest(it) }
                val audio = audioHash(source)
                var progress = -1
                VideoStampProcessor(context).writeResult(source,MediaStampRecipe("2026-11-03 10:00","设备已检查"),output) { progress=it }
                assertArrayEquals(sourceHash,MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
                assertArrayEquals(audio,audioHash(output))
                assertEquals(100,progress)
                assertFalse(File(output.path+".part").exists())
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(output.path)
                    for (time in listOf(0L,500_000L,900_000L)) {
                        val frame = checkNotNull(retriever.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST))
                        try {
                            assertEquals(if (rotation%180==0) 640 else 480,frame.width)
                            assertEquals(if (rotation%180==0) 480 else 640,frame.height)
                            var white = 0
                            for (y in 0 until frame.height) for (x in 0 until frame.width) {
                                val color = frame.getPixel(x,y)
                                if (Color.red(color)>180 && Color.green(color)>180 && Color.blue(color)>180) {
                                    assertTrue("rotation=$rotation x=$x",x > frame.width*.45f)
                                    assertTrue("rotation=$rotation y=$y",y > frame.height*.80f)
                                    white++
                                }
                            }
                            assertTrue("watermark missing rotation=$rotation time=$time",white>50)
                        } finally { frame.recycle() }
                    }
                } finally { retriever.release() }
                if (rotation==0 && androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("exportReviewSamples")=="true") {
                    val dir=File(context.filesDir,"v091-review").apply{mkdirs()}
                    source.copyTo(File(dir,"video-original.mp4"),overwrite=true)
                    output.copyTo(File(dir,"video-watermark-note.mp4"),overwrite=true)
                }
            } finally { source.delete();output.delete();File(output.path+".part").delete() }
        }
    }

    private fun audioHash(file: File, expectedMime: String = "audio/mp4a-latm"): ByteArray {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true }
            assertEquals(expectedMime,extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(track)
            val digest=MessageDigest.getInstance("SHA-256")
            val buffer=ByteBuffer.allocate(1024*1024)
            var samples=0
            while (true) {
                buffer.clear(); val count=extractor.readSampleData(buffer,0)
                if (count<0) break
                val bytes=ByteArray(count);buffer.position(0);buffer.get(bytes);digest.update(bytes);samples++
                if (!extractor.advance()) break
            }
            assertTrue(samples>0)
            return digest.digest()
        } finally { extractor.release() }
    }
}

/** Invocation-owned, camera-free H264/AAC fixture with deterministic pixels and a 997Hz tone. */
internal object VideoStampFixture {
    private data class Sample(val bytes:ByteArray,val time:Long,val flags:Int)
    private data class Encoded(val format:MediaFormat,val samples:List<Sample>)

    fun write(file:File,rotation:Int=0,audioMime:String="audio/mp4a-latm",videoOffsetUs:Long=0) {
        val videoFormat=MediaFormat.createVideoFormat("video/avc",640,480).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE,1_500_000);setInteger(MediaFormat.KEY_FRAME_RATE,30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
        }
        val audioFormat=MediaFormat.createAudioFormat(audioMime,if(audioMime=="audio/3gpp") 8000 else 44100,1).apply {
            if(audioMime=="audio/mp4a-latm") setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE,if(audioMime=="audio/3gpp") 12200 else 128000)
        }
        val video=encode(videoFormat,true);val audio=encode(audioFormat,false)
        val muxer=MediaMuxer(file.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            muxer.setOrientationHint(rotation)
            val v=muxer.addTrack(video.format);val a=muxer.addTrack(audio.format);muxer.start()
            val info=MediaCodec.BufferInfo()
            (video.samples.map { v to it.copy(time=it.time+videoOffsetUs) }+audio.samples.map { a to it }).sortedBy { it.second.time }.forEach { (track,sample) ->
                info.set(0,sample.bytes.size,sample.time,sample.flags)
                muxer.writeSampleData(track,ByteBuffer.wrap(sample.bytes),info)
            }
            muxer.stop()
        } finally { muxer.release() }
    }

    private fun encode(format:MediaFormat,video:Boolean):Encoded {
        val sampleRate=if(video) 0 else format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val codec=MediaCodec.createEncoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        val samples=mutableListOf<Sample>();var outputFormat:MediaFormat?=null
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
            var fed=0;var eos=false;var ended=false
            val info=MediaCodec.BufferInfo();val deadline=SystemClock.elapsedRealtime()+30000
            while (!ended) {
                check(SystemClock.elapsedRealtime()<deadline) { "Fixture encoder timed out" }
                if (!eos) {
                    val input=codec.dequeueInputBuffer(1000)
                    if (input>=0) {
                        if (fed >= if(video) 30 else sampleRate) {
                            codec.queueInputBuffer(input,0,0,1_000_000,MediaCodec.BUFFER_FLAG_END_OF_STREAM);eos=true
                        } else if(video) {
                            val image=checkNotNull(codec.getInputImage(input))
                            try {
                                image.planes.forEachIndexed { index,plane ->
                                    val width=if(index==0)640 else 320;val height=if(index==0)480 else 240
                                    for(y in 0 until height) for(x in 0 until width) {
                                        plane.buffer.put(y*plane.rowStride+x*plane.pixelStride,(if(index==0)50 else 128).toByte())
                                    }
                                }
                            } finally { image.close() }
                            codec.queueInputBuffer(input,0,640*480*3/2,fed*1_000_000L/30,0);fed++
                        } else {
                            val buffer=checkNotNull(codec.getInputBuffer(input)).apply { clear();order(ByteOrder.LITTLE_ENDIAN) }
                            val count=minOf(buffer.remaining()/2,sampleRate-fed)
                            repeat(count) { index -> buffer.putShort((sin((fed+index)*2*Math.PI*997/sampleRate)*5000).toInt().toShort()) }
                            codec.queueInputBuffer(input,0,count*2,fed*1_000_000L/sampleRate,0);fed+=count
                        }
                    }
                }
                when(val index=codec.dequeueOutputBuffer(info,1000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat=codec.outputFormat
                    else -> if(index>=0) {
                        if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0 && info.presentationTimeUs>=0) {
                            val buffer=checkNotNull(codec.getOutputBuffer(index));buffer.position(info.offset);buffer.limit(info.offset+info.size)
                            val bytes=ByteArray(info.size);buffer.get(bytes)
                            samples+=Sample(bytes,info.presentationTimeUs,info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                        }
                        ended=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                        codec.releaseOutputBuffer(index,false)
                    }
                }
            }
            codec.stop()
            return Encoded(checkNotNull(outputFormat),samples)
        } finally { codec.release() }
    }
}
