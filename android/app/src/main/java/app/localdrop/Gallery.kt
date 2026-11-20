package app.localdrop

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.File

/** Publishes committed received media; originals stay in the private inbox. */
object Gallery {
    private fun mediaMime(item: JSONObject): String {
        val mime = item.optString("mime")
        if (mime.startsWith("image/") || mime.startsWith("video/")) return mime
        // Windows 0.3.1 describes non-image file references as generic binary files.
        if (mime != "application/octet-stream" && mime.isNotEmpty()) return mime
        val extension = item.optString("name").trimEnd('.', ' ').substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: mime
    }

    @Synchronized fun save(context: Context, manifest: JSONObject): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        val id = manifest.getString("id")
        require(id.matches(Regex("[a-fA-F0-9]{32}")))
        val folder = File(context.filesDir, "inbox/$id").canonicalFile
        require(File(folder, "receipt.json").isFile)
        val items = manifest.getJSONArray("items")
        var saved = 0
        for (n in 0 until items.length()) {
            val item = items.getJSONObject(n)
            val mime = mediaMime(item)
            val video = mime.startsWith("video/")
            if (!video && !mime.startsWith("image/")) continue
            val name = item.getString("name").trimEnd('.', ' ')
            val source = File(folder, "%03d_%s".format(java.util.Locale.ROOT, n, name)).canonicalFile
            require(source.parentFile == folder && source.isFile)
            val bitmap = !video && (mime == "image/bmp" || source.extension.equals("bmp", true))
            val display = "LocalDrop_${id}_${n}." + if(bitmap) "png" else name.substringAfterLast('.', "img")
            val resolver = context.contentResolver
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val relativePath = "${if(video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/LocalDrop/"
            // Deterministic name avoids duplicate copies when revisiting an inbox batch.
            val exists = resolver.query(collection, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?",
                arrayOf(display, relativePath), null)?.use { it.moveToFirst() } ?: false
            if (exists) { saved++; continue }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, display)
                put(MediaStore.Images.Media.MIME_TYPE, if(bitmap) "image/png" else mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(collection, values))
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { output ->
                    if (bitmap) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(source.path, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                            bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000)
                        val image = checkNotNull(BitmapFactory.decodeFile(source.path))
                        try { check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) }
                        finally { image.recycle() }
                    } else source.inputStream().use { it.copyTo(output, 64 * 1024) }
                }
                check(resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null) == 1)
                saved++
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }
        return saved
    }

    fun summary(context: Context, result: String): String? {
        if (result.isEmpty() || result.startsWith("error:")) return null
        return runCatching {
            val manifest = JSONObject(result)
            val media = (0 until manifest.getJSONArray("items").length()).count {
                val mime = mediaMime(manifest.getJSONArray("items").getJSONObject(it))
                mime.startsWith("image/") || mime.startsWith("video/")
            }
            if (media == 0) null
            else if (Build.VERSION.SDK_INT < 29) "Media received. Open it in LocalDrop Preferences; Gallery saving requires Android 10+."
            else {
                val saved = save(context, manifest)
                "$saved photo(s)/video(s) saved to Gallery → LocalDrop. Originals remain in Preferences."
            }
        }.getOrElse { "Received, but Gallery saving failed. Originals are safe in LocalDrop Preferences." }
    }

    fun saveExisting(context: Context): Int = File(context.filesDir, "inbox").listFiles()
        ?.filter { File(it, "receipt.json").isFile }
        ?.sumOf { save(context, JSONObject(File(it, "receipt.json").readText())) } ?: 0
}
