package com.example.photocategorycamera.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MotionPhotoAssemblerTest {
    @Test
    fun xmpDeclaresMotionPhotoContainerAndVideoLength() {
        val xmp = MotionPhotoAssembler.buildXmp(videoLength = 12_345L, presentationTimestampUs = 900_000L)

        assertTrue(xmp.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(xmp.contains("GCamera:MotionPhotoVersion=\"1\""))
        assertTrue(xmp.contains("GCamera:MotionPhotoPresentationTimestampUs=\"900000\""))
        assertTrue(xmp.contains("GCamera:MicroVideoOffset=\"12345\""))
        assertTrue(xmp.contains("GCamera:MicroVideoPresentationTimestampUs=\"900000\""))
        assertTrue(xmp.contains("<Container:Item"))
        assertTrue(xmp.contains("Item:Semantic=\"Primary\""))
        assertTrue(xmp.contains("Item:Semantic=\"MotionPhoto\""))
        assertTrue(xmp.contains("Item:Length=\"12345\""))
        assertTrue(xmp.contains("<?xpacket end=\"w\"?>"))
    }

    @Test
    fun assembleKeepsJpegHeaderAndPlacesMp4AtFileEnd() {
        val directory = Files.createTempDirectory("motion-photo-test").toFile()
        val jpeg = directory.resolve("capture.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        }
        val mp4 = directory.resolve("clip.mp4").apply {
            writeBytes(byteArrayOf(0, 0, 0, 16, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 0, 0, 0, 0))
        }

        MotionPhotoAssembler.assemble(jpeg, mp4, 900_000L)

        val bytes = jpeg.readBytes()
        assertTrue(bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
        assertTrue(bytes.copyOfRange(bytes.size - mp4.length().toInt(), bytes.size).contentEquals(mp4.readBytes()))
        assertTrue(bytes.toString(Charsets.ISO_8859_1).contains("GCamera:MotionPhoto=\"1\""))
        directory.deleteRecursively()
    }
}
