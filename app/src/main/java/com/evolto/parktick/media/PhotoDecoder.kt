package com.evolto.parktick.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

object PhotoDecoder {
    fun decode(photo: File, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("照片无法读取，请重新拍照")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        val decoded = BitmapFactory.decodeFile(photo.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            if (Build.VERSION.SDK_INT >= 26) inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }) ?: throw IOException("照片解码失败")
        // A 16-bit PNG can decode to RGBA_F16 despite inPreferredConfig; JNI requires 8-bit RGBA.
        val rgba = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else {
            try {
                decoded.copy(Bitmap.Config.ARGB_8888, false) ?: throw IOException("照片格式转换失败")
            } finally {
                decoded.recycle()
            }
        }
        val exif = try { ExifInterface(photo) } catch (error: IOException) { rgba.recycle(); throw error }
        if (exif.rotationDegrees == 0 && !exif.isFlipped) return rgba
        val matrix = Matrix().apply {
            if (exif.isFlipped) postScale(-1f, 1f)
            postRotate(exif.rotationDegrees.toFloat())
        }
        val oriented = Bitmap.createBitmap(rgba, 0, 0, rgba.width, rgba.height, matrix, true)
        if (oriented !== rgba) rgba.recycle()
        return oriented
    }
}
