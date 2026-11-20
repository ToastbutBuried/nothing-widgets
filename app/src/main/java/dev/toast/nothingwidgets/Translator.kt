package dev.toast.nothingwidgets

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Translation / romanization of lyric lines through Google's free web endpoint (client=gtx, no key; unofficial,
 * so it can fail or rate-limit: every failure just means no second line). Results are cached per song in the
 * lyrics cache, so a song is sent once. Only lines are sent, never anything about the user.
 */
object Translator {
    const val OFF = 0; const val TRANSLATE = 1; const val ROMAN = 2; const val BOTH = 3
    /** romanized lines are sent joined by this; Google keeps it, but drops newlines for Japanese */
    private const val SEP = " | "
    private const val CHUNK = 3500   // chars per request, the endpoint refuses ~5000+

    /** Text lines in, the same number of lines out (may be ""). Pure, unit tested. */
    fun splitTranslated(joined: String, n: Int): List<String>? {
        val out = joined.split("\n").map { it.trim() }
        return if (out.size == n) out else null
    }

    fun splitRoman(joined: String, n: Int): List<String>? {
        val out = joined.split("|").map { it.trim() }
        return if (out.size == n) out else null
    }

    /** chunks of whole lines, each under [max] chars joined */
    fun chunks(lines: List<String>, max: Int = CHUNK): List<List<String>> {
        val out = ArrayList<List<String>>(); var cur = ArrayList<String>(); var len = 0
        for (l in lines) {
            if (cur.isNotEmpty() && len + l.length + SEP.length > max) { out.add(cur); cur = ArrayList(); len = 0 }
            cur.add(l); len += l.length + SEP.length
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private class Reply(val src: String, val translated: String, val roman: String?)

    /** One POST. null = network/format error. */
    private fun call(text: String, tl: String, dt: String): Reply? = try {
        val c = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=${enc(tl)}$dt").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
        c.outputStream.use { it.write(("q=" + enc(text)).toByteArray()) }
        if (c.responseCode != 200) null else {
            val a = JSONArray(c.inputStream.bufferedReader().readText())
            val segs = a.optJSONArray(0) ?: JSONArray()
            val tr = StringBuilder(); var roman: String? = null
            for (i in 0 until segs.length()) {
                val s = segs.optJSONArray(i) ?: continue
                if (s.isNull(0)) { if (s.length() > 3 && !s.isNull(3)) roman = s.optString(3) }   // [null,null,null,"romaji"]
                else tr.append(s.optString(0))
            }
            Reply(a.optString(2, ""), tr.toString(), roman)
        }
    } catch (_: Exception) { null }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Blocking. One sub-line per input line ("" where there is nothing to show), joined "\n" for BOTH.
     * null = failed (not cached, retried next time). All-empty = song already in the target language / Latin script.
     */
    fun fetch(ctx: Context, trackKey: String, lines: List<String>, mode: Int, tl: String): List<String>? {
        if (mode == OFF || lines.isEmpty()) return null
        val cache = Caches.lyrics(ctx)
        val key = DiskCache.key("$trackKey|tr|$mode|$tl")
        cache.get(key)?.let { f ->
            val arr = try { JSONArray(f.readText()) } catch (_: Exception) { null }
            if (arr != null && arr.length() == lines.size) return List(arr.length()) { arr.optString(it) }
        }
        // only non-empty lines go out ("♪" breaks stay empty)
        val idx = lines.indices.filter { lines[it].isNotBlank() }
        val src = idx.map { lines[it].replace("|", "/").trim() }
        val tr = arrayOfNulls<String>(src.size); val ro = arrayOfNulls<String>(src.size)
        var off = 0
        for (part in chunks(src)) {
            if (mode == TRANSLATE || mode == BOTH) {
                val r = call(part.joinToString("\n"), tl, "&dt=t") ?: return null
                if (r.src != tl) splitTranslated(r.translated, part.size)?.forEachIndexed { i, s -> tr[off + i] = s }
            }
            if (mode == ROMAN || mode == BOTH) {
                val r = call(part.joinToString(SEP), "en", "&dt=rm") ?: return null
                r.roman?.let { splitRoman(it, part.size) }?.forEachIndexed { i, s -> ro[off + i] = s }
            }
            off += part.size
        }
        val out = MutableList(lines.size) { "" }
        idx.forEachIndexed { k, li ->
            val orig = src[k]
            val t = tr[k]?.takeIf { !it.equals(orig, true) } ?: ""
            val r = ro[k]?.takeIf { !it.equals(orig, true) } ?: ""   // Latin-script songs come back unchanged
            out[li] = listOf(r, t).filter { it.isNotEmpty() }.joinToString("\n")
        }
        cache.put(key, JSONArray(out).toString().toByteArray())
        return out
    }

    /** a few common targets for the picker; anything else via the phone language */
    val LANGS = listOf("en" to "English", "de" to "Deutsch", "es" to "Español", "fr" to "Français", "it" to "Italiano",
        "pt" to "Português", "nl" to "Nederlands", "pl" to "Polski", "tr" to "Türkçe", "ja" to "日本語", "ko" to "한국어", "zh-CN" to "中文")
}
