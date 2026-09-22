package com.example.photocategorycamera.camera

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption

object MotionPhotoAssembler {
    private val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.US_ASCII)

    fun assemble(jpegFile: File, videoFile: File, presentationTimestampUs: Long) {
        require(jpegFile.isFile && jpegFile.length() > 2L) { "Live图静态照片无效" }
        require(videoFile.isFile && videoFile.length() > 0L) { "Live图视频片段无效" }
        jpegFile.inputStream().use { input ->
            if (input.read() != 0xFF || input.read() != 0xD8) throw IOException("静态照片不是有效JPEG")
        }

        val xmp = buildXmp(videoFile.length(), presentationTimestampUs)
            .toByteArray(StandardCharsets.UTF_8)
        val segmentLength = xmpHeader.size + xmp.size + 2
        if (segmentLength > 0xFFFF) throw IOException("Live图XMP元数据过大")

        val videoLength = videoFile.length()
        val output = File(jpegFile.parentFile, "${jpegFile.nameWithoutExtension}_motion.tmp")
        try {
            BufferedOutputStream(FileOutputStream(output)).use { stream ->
                stream.write(0xFF)
                stream.write(0xD8)
                stream.write(0xFF)
                stream.write(0xE1)
                stream.write(segmentLength shr 8)
                stream.write(segmentLength and 0xFF)
                stream.write(xmpHeader)
                stream.write(xmp)
                jpegFile.inputStream().buffered().use { jpeg ->
                    jpeg.skip(2)
                    jpeg.copyTo(stream)
                }
                videoFile.inputStream().buffered().use { it.copyTo(stream) }
            }
            try {
                Files.move(
                    output.toPath(),
                    jpegFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(output.toPath(), jpegFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            verifyContainer(jpegFile, videoLength)
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    fun buildXmp(videoLength: Long, presentationTimestampUs: Long): String =
        """<?xpacket begin="${'\uFEFF'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about=""
 xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
 xmlns:Container="http://ns.google.com/photos/1.0/container/"
 xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
 GCamera:MotionPhoto="1"
 GCamera:MotionPhotoVersion="1"
 GCamera:MotionPhotoPresentationTimestampUs="${presentationTimestampUs.coerceAtLeast(0L)}"
 GCamera:MicroVideo="1"
 GCamera:MicroVideoVersion="1"
 GCamera:MicroVideoOffset="$videoLength"
 GCamera:MicroVideoPresentationTimestampUs="${presentationTimestampUs.coerceAtLeast(0L)}">
<Container:Directory><rdf:Seq>
<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/></rdf:li>
<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/></rdf:li>
</rdf:Seq></Container:Directory>
</rdf:Description></rdf:RDF></x:xmpmeta>
<?xpacket end="w"?>"""

    private fun verifyContainer(file: File, videoLength: Long) {
        if (file.length() <= videoLength + 2L) throw IOException("Live图封装后长度异常")
        RandomAccessFile(file, "r").use { input ->
            if (input.readUnsignedByte() != 0xFF || input.readUnsignedByte() != 0xD8) {
                throw IOException("Live图封装后JPEG头无效")
            }
            input.seek(file.length() - videoLength + 4L)
            val boxType = ByteArray(4)
            input.readFully(boxType)
            if (!boxType.contentEquals("ftyp".toByteArray(StandardCharsets.US_ASCII))) {
                throw IOException("Live图末尾未找到有效MP4片段")
            }
        }
    }
}
