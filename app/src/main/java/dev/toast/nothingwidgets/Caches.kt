package dev.toast.nothingwidgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File

/** App-wide lyrics + album art caches, each capped by its own setting (Prefs.lyrCapMb / artCapMb). */
object Caches {
    @Volatile private var lyr: DiskCache? = null
    @Volatile private var art: DiskCache? = null
    /** a few decoded covers for instant skip-back; main thread only */
    private val artMem = LruCache<String, Bitmap>(6)
    private const val ART_MAX = 800
    /** setting in MB, 0 = unlimited */
    private fun capBytes(mb: Int) = if (mb <= 0) Long.MAX_VALUE else mb * DiskCache.MB

    fun lyrics(c: Context): DiskCache = lyr ?: synchronized(this) {
        lyr ?: run {
            val app = c.applicationContext
            DiskCache(File(app.cacheDir, "lyrics"), ".lrc") { capBytes(Prefs.lyrCapMb(app)) }.also { migrate(app, it); lyr = it }
        }
    }

    fun art(c: Context): DiskCache = art ?: synchronized(this) {
        art ?: run {
            val app = c.applicationContext
            DiskCache(File(app.cacheDir, "art"), ".img") { capBytes(Prefs.artCapMb(app)) }.also { art = it }
        }
    }

    /** v0.3 kept lyrics as cacheDir/lrc_<md5>.lrc: move them into the capped cache once. */
    private fun migrate(c: Context, d: DiskCache) {
        val old = c.cacheDir.listFiles { f -> f.name.startsWith("lrc_") && f.name.endsWith(".lrc") } ?: return
        if (old.isEmpty()) return
        d.dir.mkdirs()
        for (f in old) if (!f.renameTo(File(d.dir, f.name.removePrefix("lrc_")))) f.delete()
        d.trim()
    }

    // ---- art (key = the service's "title|artist" track key; hashed for the file name) ----
    fun artMem(key: String): Bitmap? = artMem.get(key)

    /** Blocking (io thread). Disk lookup; the result is not put in memory here, call remember() on main. */
    fun artDisk(c: Context, key: String): Bitmap? = try {
        art(c).get(DiskCache.key(key))?.let { BitmapFactory.decodeFile(it.path) }
    } catch (_: Exception) { null }

    fun remember(key: String, b: Bitmap) { artMem.put(key, b) }

    /** Blocking (io thread): scales the cover down to ART_MAX and stores it. */
    fun artPut(c: Context, key: String, b: Bitmap) {
        try {
            if (b.isRecycled) return
            val s = minOf(1f, ART_MAX / maxOf(b.width, b.height).toFloat())
            val small = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b
            val out = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 30) small.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
            else small.compress(Bitmap.CompressFormat.JPEG, 88, out)
            if (small !== b) small.recycle()
            art(c).put(DiskCache.key(key), out.toByteArray())
        } catch (_: Exception) {}
    }

    // ---- settings screen ----
    class Stats(val artCount: Int, val artBytes: Long, val lyrCount: Int, val lyrBytes: Long, val saved: Int)

    fun stats(c: Context): Stats {
        val (ac, ab) = art(c).stats(); val (lc, lb) = lyrics(c).stats()
        val saved = c.filesDir.listFiles { f -> f.name.startsWith("choice_") }?.size ?: 0
        return Stats(ac, ab, lc, lb, saved)
    }

    /** Wipes both caches; saved lyrics (filesDir) are untouched. */
    fun clear(c: Context) { art(c).clear(); lyrics(c).clear() }

    fun trim(c: Context) { art(c).trim(); lyrics(c).trim() }

    /** main thread */
    fun clearMem() { artMem.evictAll() }
}
