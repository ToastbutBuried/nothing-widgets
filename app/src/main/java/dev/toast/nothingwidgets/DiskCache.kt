package dev.toast.nothingwidgets

import java.io.File
import java.security.MessageDigest

/**
 * Size-capped LRU cache of one file per key in [dir]. A file's mtime is its last use (get touches it),
 * so trim() drops the least recently used first. Plain JVM so it is unit tested; synchronized because the
 * settings screen reads stats while the service writes.
 */
class DiskCache(val dir: File, private val suffix: String, private val capBytes: () -> Long) {
    private fun file(key: String) = File(dir, key + suffix)
    private fun entries(): List<File> = dir.listFiles()?.filter { it.isFile && it.name.endsWith(suffix) } ?: emptyList()

    @Synchronized fun get(key: String, now: Long = System.currentTimeMillis()): File? {
        val f = file(key)
        if (!f.exists()) return null
        f.setLastModified(now)
        return f
    }

    @Synchronized fun put(key: String, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, "$key.tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file(key))) { file(key).delete(); tmp.renameTo(file(key)) }
        } catch (_: Exception) { tmp.delete(); return }
        trim()
    }

    @Synchronized fun remove(key: String) { file(key).delete() }
    @Synchronized fun clear() { dir.listFiles()?.forEach { it.delete() } }

    /** count, bytes */
    @Synchronized fun stats(): Pair<Int, Long> { val e = entries(); return e.size to e.sumOf { it.length() } }

    /** Deletes least recently used entries until the total fits the cap. */
    @Synchronized fun trim() {
        val cap = capBytes()
        val e = entries()
        var total = e.sumOf { it.length() }
        if (total <= cap) return
        for (f in e.sortedBy { it.lastModified() }) {
            if (total <= cap) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    companion object {
        const val MB = 1024L * 1024L
        fun key(title: String, artist: String): String = key("$title|$artist")
        /** file-safe key: md5 of the lowercased "title|artist" track key */
        fun key(trackKey: String): String =
            MessageDigest.getInstance("MD5").digest(trackKey.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** "No lyrics on LRCLIB" is cached as a timestamped marker so it can be retried after a while. */
object NotFound {
    private const val MARK = "#NOTFOUND "
    fun marker(now: Long) = "$MARK$now"
    /** legacy caches stored "" for not found */
    fun isMarker(text: String) = text.isEmpty() || text.startsWith(MARK)
    /** [days] <= 0 = never retry. A legacy empty marker has no date, so it is retried once. */
    fun expired(text: String, now: Long, days: Int): Boolean {
        val at = text.removePrefix(MARK).trim().toLongOrNull() ?: return true
        if (days <= 0) return false
        return now - at > days * 86_400_000L
    }
}
