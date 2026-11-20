package dev.toast.nothingwidgets

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One JSON file with every setting, per-song latencies and saved lyrics. Caches are left out (they refill). */
object Backup {
    private const val MAGIC = "nothing-widgets-backup"

    fun export(c: Context): String {
        val prefs = JSONObject()
        for ((k, v) in Prefs.all(c)) {
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            prefs.put(k, e)
        }
        val saved = JSONObject()
        c.filesDir.listFiles { f -> f.name.startsWith("choice_") }?.forEach { saved.put(it.name, it.readText()) }
        val lat = try { JSONObject(File(c.filesDir, "latency.json").readText()) } catch (_: Exception) { JSONObject() }
        return JSONObject().put("app", MAGIC).put("version", 1).put("prefs", prefs).put("latency", lat).put("saved", saved).toString(1)
    }

    /** Replaces settings, latencies and saved lyrics. Returns the number of saved songs restored; throws on a foreign file. */
    fun import(c: Context, text: String): Int {
        val o = JSONObject(text)
        require(o.optString("app") == MAGIC) { "not a Nothing Widgets backup" }
        val ed = Prefs.editor(c).clear()
        val prefs = o.optJSONObject("prefs") ?: JSONObject()
        for (k in prefs.keys()) {
            val e = prefs.getJSONObject(k)
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
                "set" -> { val a = e.getJSONArray("v"); ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
            }
        }
        ed.commit()
        o.optJSONObject("latency")?.let { File(c.filesDir, "latency.json").writeText(it.toString()) }
        val saved = o.optJSONObject("saved") ?: JSONObject()
        var n = 0
        for (name in saved.keys()) {
            if (!name.matches(Regex("choice_[0-9a-f]{32}\\.lrc"))) continue   // never write outside our own file pattern
            File(c.filesDir, name).writeText(saved.getString(name)); n++
        }
        return n
    }
}
