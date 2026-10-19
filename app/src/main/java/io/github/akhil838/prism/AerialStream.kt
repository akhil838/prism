package io.github.akhil838.prism

import android.media.MediaDataSource
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * Feeds MediaPlayer an aerial clip that Prism downloads itself, at full connection speed, into a
 * temporary file. Android's own HTTP streaming pulls Apple's CDN at ~3 Mbps (below the clips'
 * ~6 Mbps), so playback kept pausing to buffer and sometimes froze for good.
 *
 * Apple's .mov files keep their index (moov) at the end: reads far ahead of the download are
 * served by small range requests. Everything else waits for the sequential download, which runs
 * ~10x faster than playback. [close] stops the download; the owner deletes [file].
 */
class AerialStream(private val url: String, val file: File) : MediaDataSource() {
    private val lock = Object()
    @Volatile private var total = -1L
    /** Contiguous bytes on disk from offset 0. */
    @Volatile private var have = 0L
    @Volatile private var failed = false
    @Volatile private var closed = false
    private val reader = RandomAccessFile(file.apply { delete() }, "rw")
    /** Side-loaded ranges ahead of the download (the moov index at the end of the file). */
    private val side = ArrayList<Pair<Long, ByteArray>>()

    init {
        Thread({ download() }, "AerialStream").apply { isDaemon = true; start() }
    }

    private fun download() {
        var attempts = 0
        file.outputStream().use { out ->
            while (!closed && (total < 0 || have < total)) {
                val before = have
                val ok = runCatching {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 10_000; c.readTimeout = 15_000
                    if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                    try {
                        if (total < 0) {
                            total = c.contentLengthLong
                            synchronized(lock) { lock.notifyAll() }
                        }
                        c.inputStream.use { input ->
                            val buf = ByteArray(256 * 1024)
                            while (!closed) {
                                val r = input.read(buf); if (r < 0) break
                                out.write(buf, 0, r)
                                synchronized(lock) { have += r; lock.notifyAll() }
                            }
                        }
                    } finally { c.disconnect() }
                }.onFailure { Log.w(TAG, "download at ${have shr 20} MB: $it") }.isSuccess
                // Each pass resumes with a Range request; give up after 4 passes without progress.
                if (have == before || !ok) { if (++attempts > 3) break } else attempts = 0
            }
        }
        if (!closed && (total < 0 || have < total)) failed = true
        synchronized(lock) { lock.notifyAll() }
    }

    override fun getSize(): Long {
        synchronized(lock) {
            val until = System.currentTimeMillis() + WAIT_MS
            while (total < 0 && !failed && !closed && System.currentTimeMillis() < until) lock.wait(500)
        }
        return total
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val len = getSize()
        if (len < 0 || closed) return -1
        if (position >= len) return -1
        val n = minOf(size.toLong(), len - position).toInt()
        // Far ahead of the download (the index at the end): fetch just that range.
        if (position > have + AHEAD_GAP) return sideRead(position, buffer, offset, n, len)
        synchronized(lock) {
            val until = System.currentTimeMillis() + WAIT_MS
            while (have <= position && !failed && !closed && System.currentTimeMillis() < until) lock.wait(500)
        }
        if (have <= position) return -1
        val count = minOf(n.toLong(), have - position).toInt()
        return reader.channel.read(ByteBuffer.wrap(buffer, offset, count), position)
    }

    private fun sideRead(position: Long, buffer: ByteArray, offset: Int, n: Int, len: Long): Int {
        synchronized(side) {
            side.firstOrNull { (start, bytes) -> position >= start && position + n <= start + bytes.size }?.let { (start, bytes) ->
                System.arraycopy(bytes, (position - start).toInt(), buffer, offset, n); return n
            }
        }
        val end = minOf(len, position + maxOf(n.toLong(), SIDE_CHUNK)) - 1
        val bytes = runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("Range", "bytes=$position-$end")
                try { inputStream.readBytes() } finally { disconnect() }
            }
        }.onFailure { Log.w(TAG, "range read: $it") }.getOrNull() ?: return -1
        synchronized(side) { side += position to bytes; while (side.size > SIDE_MAX) side.removeAt(0) }
        val count = minOf(n, bytes.size)
        System.arraycopy(bytes, 0, buffer, offset, count)
        return count
    }

    override fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        runCatching { reader.close() }
    }

    private companion object {
        const val TAG = "PrismAerials"
        const val WAIT_MS = 20_000L
        const val AHEAD_GAP = 16L shl 20
        const val SIDE_CHUNK = 2L shl 20
        const val SIDE_MAX = 6
    }
}
