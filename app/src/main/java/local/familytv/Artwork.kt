package local.familytv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class Artwork(private val disk: File) {
    private val worker = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private var storedSinceSweep = 0

    fun bind(view: ImageView, url: String?, width: Int, height: Int, placeholder: Int = Color.TRANSPARENT) {
        (view.tag as? Request)?.cancelled = true
        view.setImageDrawable(null)
        if (view.background == null) view.setBackgroundColor(placeholder)
        if (url.isNullOrBlank()) return
        val request = Request()
        view.tag = request
        val key = "${width}x${height}:$url"
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        worker.execute {
            if (request.cancelled) return@execute
            val bitmap = cache.get(key) ?: runCatching { thumbnail(key, url, width, height) }.getOrNull()
            if (bitmap != null) {
                cache.put(key, bitmap)
                main.post {
                    if (!request.cancelled && view.tag === request) view.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun thumbnail(key: String, url: String, width: Int, height: Int): Bitmap? {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(disk, "$hash.jpg")
        if (target.isFile) {
            BitmapFactory.decodeFile(target.path)?.let {
                target.setLastModified(System.currentTimeMillis())
                return it
            }
        }
        val bitmap = decode(url, width, height) ?: return null
        runCatching { storeThumbnail(target, bitmap) }
        return bitmap
    }

    @Synchronized
    private fun storeThumbnail(target: File, bitmap: Bitmap) {
        if (target.exists() || (!disk.isDirectory && !disk.mkdirs())) return
        val temporary = File.createTempFile("art-", ".part", disk)
        try {
            val written = temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it) }
            if (written) temporary.renameTo(target)
        } finally {
            temporary.delete()
        }
        // Sweeping the whole directory on every save is wasteful during fast scrolling.
        // Run it periodically instead; the on-disk cap is a soft limit.
        if (++storedSinceSweep < SWEEP_INTERVAL) return
        storedSinceSweep = 0
        val entries = disk.listFiles { file -> file.extension == "jpg" }.orEmpty()
            .sortedBy(File::lastModified)
        var bytes = entries.sumOf(File::length)
        for (old in entries) {
            if (bytes <= MAX_DISK_BYTES) break
            val length = old.length()
            if (old.delete()) bytes -= length
        }
    }

    private fun decode(address: String, width: Int, height: Int): Bitmap? {
        val url = URL(address)
        if (url.protocol !in setOf("http", "https")) return null
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
        }
        val bytes = try {
            if (connection.responseCode != 200) return null
            connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    if (output.size() + count > MAX_IMAGE_BYTES) return null
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = 1
        while (options.outWidth / options.inSampleSize > width * 2 ||
            options.outHeight / options.inSampleSize > height * 2
        ) {
            options.inSampleSize *= 2
        }
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    fun close() {
        worker.shutdownNow()
    }

    private class Request {
        @Volatile var cancelled = false
    }

    private companion object {
        const val SWEEP_INTERVAL = 16
        const val MAX_DISK_BYTES = 16 * 1024 * 1024L
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    }
}
