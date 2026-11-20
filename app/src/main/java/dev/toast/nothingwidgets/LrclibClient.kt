package dev.toast.nothingwidgets

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Candidate(val id: Long, val track: String, val artist: String, val duration: Double, val synced: String) {
    fun label(): String {
        val m = (duration / 60).toInt(); val s = (duration % 60).toInt()
        val lines = synced.count { it == '\n' } + 1
        return "$track — $artist\n$m:" + s.toString().padStart(2, '0') + " · $lines lines"
    }
}

/**
 * Per-song latency (ms) in files/latency.json, plus album- and artist-wide defaults: most timing drift comes from how an
 * album was mastered/uploaded, so one fix can cover the rest of it. Song keys are "title|artist"; defaults live under
 * "album:<artist>|<album>" and "artist:<artist>"; "@album" maps each song with its own value to its album.
 */
object LatencyStore {
    private fun file(c: Context) = File(c.filesDir, "latency.json")
    private fun read(c: Context): JSONObject = try { JSONObject(file(c).readText()) } catch (_: Exception) { JSONObject() }
    private fun write(c: Context, o: JSONObject) = file(c).writeText(o.toString())
    fun albumKey(artist: String, album: String) = if (album.isEmpty()) "" else "album:${artist.lowercase()}|${album.lowercase()}"
    fun artistKey(artist: String) = if (artist.isEmpty()) "" else "artist:${artist.lowercase()}"

    /** where the latency in effect came from */
    enum class Src { NONE, SONG, ALBUM, ARTIST, GUESS }

    /**
     * Song value, else the album default, else the artist default, else a guess: when at least two other songs of this
     * album have their own value and agree within 300 ms, their average.
     */
    fun resolve(c: Context, key: String, artist: String, album: String): Pair<Int, Src> {
        if (key.isEmpty()) return 0 to Src.NONE
        val o = read(c)
        if (o.has(key)) return o.optInt(key) to Src.SONG
        val ak = albumKey(artist, album)
        if (ak.isNotEmpty() && o.has(ak)) return o.optInt(ak) to Src.ALBUM
        val rk = artistKey(artist)
        if (rk.isNotEmpty() && o.has(rk)) return o.optInt(rk) to Src.ARTIST
        if (ak.isNotEmpty()) {
            val links = o.optJSONObject("@album") ?: JSONObject()
            val vals = links.keys().asSequence().filter { links.optString(it) == ak && it != key && o.has(it) }.map { o.optInt(it) }.toList()
            if (vals.size >= 2 && vals.max() - vals.min() <= 300) return (vals.average() / 50).toInt() * 50 to Src.GUESS
        }
        return 0 to Src.NONE
    }

    fun set(c: Context, key: String, ms: Int, artist: String = "", album: String = "") {
        if (key.isEmpty()) return
        val o = read(c)
        val links = o.optJSONObject("@album") ?: JSONObject()
        if (ms == 0) { o.remove(key); links.remove(key) }
        else { o.put(key, ms); albumKey(artist, album).takeIf { it.isNotEmpty() }?.let { links.put(key, it) } }
        o.put("@album", links)
        write(c, o)
    }

    /** album- or artist-wide default; 0 removes it */
    fun setScope(c: Context, scopeKey: String, ms: Int) {
        if (scopeKey.isEmpty()) return
        val o = read(c)
        if (ms == 0) o.remove(scopeKey) else o.put(scopeKey, ms)
        write(c, o)
    }
}

object LrclibClient {
    /** saved lyrics (picked or saved by hand) live in filesDir: outside the cache cap and the system's cache cleanup */
    private fun choiceFile(c: Context, t: String, a: String) = File(c.filesDir, "choice_" + DiskCache.key(t, a) + ".lrc")

    /** drops the cached auto match (or not-found marker); saved lyrics stay */
    fun clearCached(c: Context, t: String, a: String) = Caches.lyrics(c).remove(DiskCache.key(t, a))
    fun saveChoice(c: Context, t: String, a: String, lrc: String) = choiceFile(c, t, a).writeText(lrc)
    fun isSaved(c: Context, t: String, a: String) = choiceFile(c, t, a).exists()
    fun unsave(c: Context, t: String, a: String) { choiceFile(c, t, a).delete() }

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

    /** Blocking. Order: saved, then cache, then best auto match. "" = none, null = offline, PLAIN prefix = unsynced text. */
    fun fetch(ctx: Context, title: String, artist: String, durationSec: Long?): String? {
        val ch = choiceFile(ctx, title, artist)
        if (ch.exists()) return ch.readText()
        val cache = Caches.lyrics(ctx); val k = DiskCache.key(title, artist)
        cache.get(k)?.let { f ->
            val t = try { f.readText() } catch (_: Exception) { null }
            if (t != null && !NotFound.isMarker(t)) return t
            if (t != null && !NotFound.expired(t, System.currentTimeMillis(), Prefs.notFoundDays(ctx))) return ""
            cache.remove(k)   // not-found marker is old enough: ask LRCLIB again
        }
        val r = search(title, artist) ?: return null
        val out = pickBest(r.synced, durationSec)?.synced ?: r.plain?.let { PLAIN + it } ?: ""
        cache.put(k, (out.ifEmpty { NotFound.marker(System.currentTimeMillis()) }).toByteArray())
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
