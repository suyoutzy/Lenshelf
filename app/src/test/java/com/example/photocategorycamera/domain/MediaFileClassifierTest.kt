package com.example.photocategorycamera.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFileClassifierTest {
    @Test fun acceptsPhotoAndVideoMimeTypesAndProviderExtensionFallback() {
        assertTrue(MediaFileClassifier.isMedia("motion.jpg", "image/jpeg"))
        assertTrue(MediaFileClassifier.isMedia("capture", "video/mp4"))
        assertTrue(MediaFileClassifier.isMedia("photo.HEIC", "application/octet-stream"))
        assertTrue(MediaFileClassifier.isMedia("movie.MP4", null))
    }

    @Test fun rejectsFoldersHiddenFilesAudioAndSidecars() {
        assertFalse(MediaFileClassifier.isMedia("folder.mp4", "vnd.android.document/directory"))
        assertFalse(MediaFileClassifier.isMedia(".hidden.jpg", "image/jpeg"))
        assertFalse(MediaFileClassifier.isMedia("voice.m4a", "audio/mp4"))
        assertFalse(MediaFileClassifier.isMedia("photo.xmp", "application/xml"))
        assertFalse(MediaFileClassifier.isMedia("notes.txt", null))
    }
}
