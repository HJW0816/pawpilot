package com.estareng.pawpilot.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.AtomicFile
import java.io.File

/** App-private, bounded, re-encoded image: no retained external URI or EXIF metadata. */
class PetPhotoStore(context: Context) {
    private val context = context.applicationContext
    private val file = AtomicFile(File(context.filesDir, "pet-profile.png"))

    fun load(): Bitmap? = runCatching {
        file.openRead().use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    fun import(uri: Uri): Bitmap {
        val temporary = File.createTempFile("pet-photo-", ".input", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 20L * 1024 * 1024) { "photo_too_large" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("photo_unavailable")
            // ImageDecoder applies EXIF orientation before the bounded output is saved.
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(temporary)) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                require(width > 0 && height > 0)
                val scale = minOf(1.0, 768.0 / maxOf(width, height))
                decoder.setTargetSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
            }
            save(bitmap)
            return bitmap
        } finally { temporary.delete() }
    }

    fun save(bitmap: Bitmap) {
        val output = file.startWrite()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            file.finishWrite(output)
        } catch (error: Exception) { file.failWrite(output); throw error }
    }

    fun remove() {
        file.delete()
        check(!file.baseFile.exists()) { "photo_delete_failed" }
    }
}
