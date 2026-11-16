package dev.toast.nothingwidgets

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

object LrclibClient {
    private fun key(t: String, a: String): String =
        MessageDigest.getInstance("MD5").digest("$t|$a".lowercase().toByteArray()).joinToString("") { "%02x".format(it) }

    /** Blocking; call off the main thread. Returns synced LRC text, "" if none exists, null on network error. */
    fun fetch(ctx: Context, title: String, artist: String, durationSec: Long?): String? {
        val f = File(ctx.cacheDir, "lrc_" + key(title, artist) + ".lrc")
        if (f.exists()) return f.readText()
        try {
            val q = "https://lrclib.net/api/search?track_name=" + URLEncoder.encode(title, "UTF-8") +
                "&artist_name=" + URLEncoder.encode(artist, "UTF-8")
            val c = URL(q).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "NothingWidgets/0.1")
            if (c.responseCode != 200) return null
            val arr = JSONArray(c.inputStream.bufferedReader().readText())
            var best: String? = null; var bestDiff = Double.MAX_VALUE
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = o.optString("syncedLyrics", "")
                if (s.isEmpty() || o.isNull("syncedLyrics")) continue
                val diff = if (durationSec != null && durationSec > 0) Math.abs(o.optDouble("duration", 0.0) - durationSec) else 0.0
                if (diff < bestDiff) { bestDiff = diff; best = s }
            }
            val out = best ?: ""
            f.writeText(out)
            return out
        } catch (e: Exception) { return null }
    }
}
