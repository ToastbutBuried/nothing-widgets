package dev.toast.nothingwidgets

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class Candidate(val id: Long, val track: String, val artist: String, val duration: Double, val synced: String) {
    fun label(): String {
        val m = (duration / 60).toInt(); val s = (duration % 60).toInt()
        val lines = synced.count { it == '\n' } + 1
        return "$track — $artist\n$m:" + s.toString().padStart(2, '0') + " · $lines lines"
    }
}

/** Remembers a per-song latency (ms) in files/latency.json; applied again whenever that song plays. */
object LatencyStore {
    private fun file(c: Context) = File(c.filesDir, "latency.json")
    private fun read(c: Context): JSONObject = try { JSONObject(file(c).readText()) } catch (_: Exception) { JSONObject() }
    fun get(c: Context, key: String): Int = if (key.isEmpty()) 0 else read(c).optInt(key, 0)
    fun set(c: Context, key: String, ms: Int) {
        if (key.isEmpty()) return
        val o = read(c)
        if (ms == 0) o.remove(key) else o.put(key, ms)
        file(c).writeText(o.toString())
    }
}

object LrclibClient {
    private fun key(t: String, a: String): String =
        MessageDigest.getInstance("MD5").digest("$t|$a".lowercase().toByteArray()).joinToString("") { "%02x".format(it) }

    private fun cacheFile(c: Context, t: String, a: String) = File(c.cacheDir, "lrc_" + key(t, a) + ".lrc")
    private fun choiceFile(c: Context, t: String, a: String) = File(c.filesDir, "choice_" + key(t, a) + ".lrc")

    fun clear(c: Context, t: String, a: String) { cacheFile(c, t, a).delete(); choiceFile(c, t, a).delete() }
    fun saveChoice(c: Context, t: String, a: String, lrc: String) = choiceFile(c, t, a).writeText(lrc)

    private fun get(url: String): JSONArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "NothingWidgets/0.1")
        if (c.responseCode == 200) JSONArray(c.inputStream.bufferedReader().readText()) else null
    } catch (e: Exception) { null }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Synced candidates plus the first plain-only text, if any. */
    class Result(val synced: List<Candidate>, val plain: String?)

    /** Searches raw metadata, then cleaned title+artist, then cleaned title alone; merged, deduped. null = network error. */
    fun search(title: String, artist: String): Result? {
        val (ct, ca) = TitleCleaner.clean(title, artist)
        val urls = LinkedHashSet<String>()
        urls.add("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}")
        if (ct.isNotEmpty()) {
            urls.add("https://lrclib.net/api/search?track_name=${enc(ct)}&artist_name=${enc(ca)}")
            urls.add("https://lrclib.net/api/search?q=${enc("$ct $ca".trim())}")
            urls.add("https://lrclib.net/api/search?track_name=${enc(ct)}")
        }
        val seen = HashSet<Long>(); val out = ArrayList<Candidate>(); var plain: String? = null; var any = false
        for (u in urls) {
            val arr = get(u) ?: continue
            any = true
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!seen.add(o.optLong("id"))) continue
                val s = if (o.isNull("syncedLyrics")) "" else o.optString("syncedLyrics", "")
                if (s.isEmpty()) {
                    if (plain == null && !o.isNull("plainLyrics")) plain = o.optString("plainLyrics", "").ifEmpty { null }
                    continue
                }
                out.add(Candidate(o.optLong("id"), o.optString("trackName"), o.optString("artistName"), o.optDouble("duration", 0.0), s))
            }
            if (out.size >= 6) break   // enough choices, save requests
        }
        return if (any) Result(out, plain) else null
    }

    const val PLAIN = "#PLAIN\n"

    /** Blocking. Order: your pick, then cache, then best auto match. "" = none, null = offline, PLAIN prefix = unsynced text. */
    fun fetch(ctx: Context, title: String, artist: String, durationSec: Long?): String? {
        val ch = choiceFile(ctx, title, artist)
        if (ch.exists()) return ch.readText()
        val f = cacheFile(ctx, title, artist)
        if (f.exists()) return f.readText()
        val r = search(title, artist) ?: return null
        val out = pickBest(r.synced, durationSec)?.synced ?: r.plain?.let { PLAIN + it } ?: ""
        f.writeText(out)
        return out
    }

    /** closest duration within 3 s wins, otherwise the first hit */
    fun pickBest(list: List<Candidate>, durationSec: Long?): Candidate? {
        if (durationSec != null && durationSec > 0) {
            val near = list.filter { Math.abs(it.duration - durationSec) <= 3 }.minByOrNull { Math.abs(it.duration - durationSec) }
            if (near != null) return near
        }
        return list.firstOrNull()
    }
}
