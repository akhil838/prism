package io.github.akhil838.prism

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Profile avatar from DiceBear (https://www.dicebear.com). Only CC0 styles are offered, so no
 * attribution is required. The chosen avatar is downloaded once and kept in app storage;
 * the network is used only while picking.
 */
object Avatar {
    /** DiceBear style id -> display name. All CC0 1.0 per dicebear.com/licenses. */
    val STYLES = linkedMapOf(
        "notionists" to "Notionists",
        "lorelei" to "Lorelei",
        "open-peeps" to "Open Peeps",
        "thumbs" to "Thumbs",
        "pixel-art" to "Pixel Art",
        "glass" to "Glass",
    )

    private val io = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    private fun file(ctx: Context) = File(ctx.filesDir, "avatar.png")

    fun url(style: String, seed: String, size: Int = 128) =
        "https://api.dicebear.com/9.x/$style/png?size=$size&seed=" + URLEncoder.encode(seed, "UTF-8")

    /** Saved avatar, or null if none chosen. */
    fun load(ctx: Context): Bitmap? = file(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun clear(ctx: Context) { file(ctx).delete() }

    /** Downloads [url] off the main thread; [done] runs on the main thread (null on failure). */
    fun fetch(url: String, done: (ByteArray?) -> Unit) {
        io.execute {
            val bytes = runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                try { if (c.responseCode == 200) c.inputStream.readBytes() else null } finally { c.disconnect() }
            }.getOrNull()
            main.post { done(bytes) }
        }
    }

    fun save(ctx: Context, bytes: ByteArray): Bitmap? {
        file(ctx).writeBytes(bytes)
        return load(ctx)
    }

    /** Eight variations per style: fixed seeds so the picker is stable between openings. */
    val SEEDS = listOf("Akhil", "Nova", "Kiran", "Milo", "Zara", "Arjun", "Luna", "Rio")
}
