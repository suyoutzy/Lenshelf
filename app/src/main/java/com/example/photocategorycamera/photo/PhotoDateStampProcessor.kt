package com.example.photocategorycamera.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

internal class InvalidPhotoSourceException(message: String) : IOException(message)

fun interface PhotoStampWriter {
    fun writeResult(source: File, text: String, output: File)
}

/** Draw in upright display coordinates, retaining raw pixel dimensions and the original EXIF transform. */
class PhotoDateStampProcessor : PhotoStampWriter {
    override fun writeResult(source: File, text: String, output: File) {
        require(source.canonicalFile != output.canonicalFile)
        require(text.isNotBlank())
        if (!source.isFile || source.length() <= 2) throw InvalidPhotoSourceException("照片原图不存在或不完整")
        RandomAccessFile(source, "r").use {
            it.seek(it.length() - 2)
            if (it.readUnsignedByte() != 255 || it.readUnsignedByte() != 217)
                throw InvalidPhotoSourceException("照片原图未拍摄完整")
        }
        val exif = ExifInterface(source)
        val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw InvalidPhotoSourceException("无法解码水印原图")
        val part = File(output.path + ".part")
        try {
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1).takeIf { it in 1..8 } ?: 1
            val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
            val raw = floatArrayOf(0f, 0f, w, 0f, 0f, h)
            val upright = when (orientation) {
                2 -> floatArrayOf(w, 0f, 0f, 0f, w, h)
                3 -> floatArrayOf(w, h, 0f, h, w, 0f)
                4 -> floatArrayOf(0f, h, w, h, 0f, 0f)
                5 -> floatArrayOf(0f, 0f, 0f, w, h, 0f)
                6 -> floatArrayOf(h, 0f, h, w, 0f, 0f)
                7 -> floatArrayOf(h, w, h, 0f, 0f, w)
                8 -> floatArrayOf(0f, w, 0f, 0f, h, w)
                else -> raw
            }
            val transform = Matrix().apply { check(setPolyToPoly(raw, 0, upright, 0, 3)) }
            val inverse = Matrix().also { check(transform.invert(it)) }
            val width = if (orientation >= 5) h else w
            val height = if (orientation >= 5) w else h
            val canvas = Canvas(bitmap)
            canvas.concat(inverse)
            MediaStampRenderer.draw(canvas, width, height, text)
            output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
            FileOutputStream(part).use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) { "无法写入日期水印" }
                it.flush(); it.fd.sync()
            }
            ExifInterface(part).apply {
                for (tag in retainedTags) exif.getAttribute(tag)?.let { setAttribute(tag, it) }
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            RandomAccessFile(part, "rw").use { it.fd.sync() }
            check(part.renameTo(output)) { "无法完成日期水印写入" }
        } finally { bitmap.recycle(); part.delete() }
    }

    private companion object {
        val retainedTags = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP,
        )
    }
}
