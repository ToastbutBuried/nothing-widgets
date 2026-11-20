package dev.toast.nothingwidgets

import android.content.Context
import org.json.JSONObject

/** Look presets: every visual setting of the three widgets at once. Built-ins plus the user's own (saved in prefs). */
object Presets {
    /** the current look, defaults included */
    fun current(c: Context): JSONObject = JSONObject()
        .put("lyrSize", Prefs.lyrSize(c)).put("lyrRed", Prefs.lyrRed(c)).put("lyrCtx", Prefs.lyrContext(c))
        .put("lyrCtl", Prefs.lyrControls(c)).put("red", Prefs.red(c)).put("vizStyle", Prefs.vizStyle(c))
        .put("bands", Prefs.bands(c)).put("peaks", Prefs.peaks(c)).put("artRadius", Prefs.artRadius(c))
        .put("artProg", Prefs.artProgress(c)).put("accCover", Prefs.accentCover(c))

    val BUILT_IN: List<Pair<String, JSONObject>> = listOf(
        "Nothing" to JSONObject("""{"lyrSize":1,"lyrRed":true,"lyrCtx":true,"lyrCtl":true,"red":false,"vizStyle":1,"bands":0,"peaks":true,"artRadius":19,"artProg":true,"accCover":false}"""),
        "Mono" to JSONObject("""{"lyrSize":1,"lyrRed":false,"lyrCtx":true,"lyrCtl":true,"red":false,"vizStyle":0,"bands":0,"peaks":false,"artRadius":19,"artProg":true,"accCover":false}"""),
        "Cover" to JSONObject("""{"lyrSize":1,"lyrRed":true,"lyrCtx":true,"lyrCtl":true,"red":true,"vizStyle":4,"bands":12,"peaks":true,"artRadius":28,"artProg":true,"accCover":true}"""),
        "Minimal" to JSONObject("""{"lyrSize":2,"lyrRed":false,"lyrCtx":false,"lyrCtl":false,"red":false,"vizStyle":3,"bands":8,"peaks":false,"artRadius":12,"artProg":false,"accCover":false}"""),
    )

    fun saved(c: Context): List<Pair<String, JSONObject>> {
        val o = try { JSONObject(Prefs.presets(c)) } catch (_: Exception) { JSONObject() }
        return o.keys().asSequence().map { it to o.getJSONObject(it) }.toList()
    }

    fun save(c: Context, name: String) {
        val o = try { JSONObject(Prefs.presets(c)) } catch (_: Exception) { JSONObject() }
        Prefs.setPresets(c, o.put(name, current(c)).toString())
    }

    fun delete(c: Context, name: String) {
        val o = try { JSONObject(Prefs.presets(c)) } catch (_: Exception) { JSONObject() }
        o.remove(name); Prefs.setPresets(c, o.toString())
    }

    fun apply(c: Context, look: JSONObject) {
        val ed = Prefs.editor(c)
        for (k in look.keys()) when (val v = look.get(k)) {
            is Boolean -> ed.putBoolean(k, v)
            is Int -> ed.putInt(k, v)
        }
        ed.commit()
    }

    /** true when the current look equals [look] (for highlighting the active preset) */
    fun matches(c: Context, look: JSONObject): Boolean {
        val cur = current(c)
        return look.keys().asSequence().all { cur.opt(it)?.toString() == look.opt(it)?.toString() }
    }
}
