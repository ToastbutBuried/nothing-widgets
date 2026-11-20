package dev.toast.nothingwidgets

import android.content.Context

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("nw", Context.MODE_PRIVATE)
    private fun b(c: Context, k: String, d: Boolean) = p(c).getBoolean(k, d)
    private fun setB(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).apply()
    private fun i(c: Context, k: String, d: Int) = p(c).getInt(k, d)
    private fun setI(c: Context, k: String, v: Int) = p(c).edit().putInt(k, v).apply()

    // visualizer
    fun red(c: Context) = b(c, "red", false)
    fun setRed(c: Context, v: Boolean) = setB(c, "red", v)
    /** sensitivity multiplier 0.5..4 */
    fun gain(c: Context) = p(c).getFloat("gain", 1.5f)
    fun setGain(c: Context, v: Float) = p(c).edit().putFloat("gain", v).apply()
    /** 0 dots, 1 bars, 2 mirror, 3 wave, 4 ring. Old installs only had the "bars" switch. */
    fun vizStyle(c: Context) = p(c).getInt("vizStyle", if (b(c, "bars", true)) VIZ_BARS else VIZ_DOTS)
    fun setVizStyle(c: Context, v: Int) = setI(c, "vizStyle", v)
    /** smoothed (cava) levels: every style but plain dots */
    fun bars(c: Context) = vizStyle(c) != VIZ_DOTS
    const val VIZ_DOTS = 0; const val VIZ_BARS = 1; const val VIZ_MIRROR = 2; const val VIZ_WAVE = 3; const val VIZ_RING = 4
    /** 0 = auto (from widget shape), else 8 / 12 / 16 */
    fun bands(c: Context) = i(c, "bands", 0)
    fun setBands(c: Context, v: Int) = setI(c, "bands", v)
    fun peaks(c: Context) = b(c, "peaks", true)
    fun setPeaks(c: Context, v: Boolean) = setB(c, "peaks", v)

    // lyrics widget
    /** 0 = S, 1 = M, 2 = L */
    fun lyrSize(c: Context) = i(c, "lyrSize", 1)
    fun setLyrSize(c: Context, v: Int) = setI(c, "lyrSize", v)
    /** true = red accents (dot, sung words), false = mono */
    fun lyrRed(c: Context) = b(c, "lyrRed", true)
    fun setLyrRed(c: Context, v: Boolean) = setB(c, "lyrRed", v)
    fun lyrContext(c: Context) = b(c, "lyrCtx", true)
    fun setLyrContext(c: Context, v: Boolean) = setB(c, "lyrCtx", v)
    fun lyrControls(c: Context) = b(c, "lyrCtl", true)
    fun setLyrControls(c: Context, v: Boolean) = setB(c, "lyrCtl", v)
    /** tap a lyric line on the widget to seek to its start */
    fun lyrSeek(c: Context) = b(c, "lyrSeek", true)
    fun setLyrSeek(c: Context, v: Boolean) = setB(c, "lyrSeek", v)

    // artwork widget
    /** 0 = album art, 1 = lyrics (swiped up) */
    fun artMode(c: Context) = i(c, "artMode", 0)
    fun setArtMode(c: Context, v: Int) = setI(c, "artMode", v)
    /** corner radius in dp; 19 matches Nothing's own music widget (measured on the phone) */
    fun artRadius(c: Context) = i(c, "artRadius", 19)
    fun setArtRadius(c: Context, v: Int) = setI(c, "artRadius", v)

    /** thin dotted progress line along the bottom of the cover */
    fun artProgress(c: Context) = b(c, "artProg", true)
    fun setArtProgress(c: Context, v: Boolean) = setB(c, "artProg", v)

    // look
    /** accent (red everywhere) taken from the album cover instead */
    fun accentCover(c: Context) = b(c, "accCover", false)
    fun setAccentCover(c: Context, v: Boolean) = setB(c, "accCover", v)

    // lyrics extras
    /** second line under the current lyric: 0 off, 1 translation, 2 romanized, 3 both */
    fun trMode(c: Context) = i(c, "trMode", 0)
    fun setTrMode(c: Context, v: Int) = setI(c, "trMode", v)
    /** translate into this language (ISO code); "" = phone language */
    fun trLang(c: Context): String = p(c).getString("trLang", "")!!.ifEmpty { java.util.Locale.getDefault().language }
    fun setTrLang(c: Context, v: String) = p(c).edit().putString("trLang", v).apply()
    /** current line as a silent notification (lock screen / shade) */
    fun lockLyrics(c: Context) = b(c, "lockLyr", false)
    fun setLockLyrics(c: Context, v: Boolean) = setB(c, "lockLyr", v)

    // apps
    /** media apps the widgets ignore (package names) */
    fun ignoredApps(c: Context): Set<String> = p(c).getStringSet("ignApps", emptySet())!!.toSet()
    fun setIgnoredApps(c: Context, v: Set<String>) = p(c).edit().putStringSet("ignApps", v).apply()
    /** every media app seen so far, for the filter list */
    fun seenApps(c: Context): Set<String> = p(c).getStringSet("seenApps", emptySet())!!.toSet()
    fun addSeenApp(c: Context, pkg: String) { val s = seenApps(c); if (pkg !in s) p(c).edit().putStringSet("seenApps", s + pkg).apply() }

    /** look presets saved by the user: JSON {name: {prefKey: value}} */
    fun presets(c: Context): String = p(c).getString("presets", "{}")!!
    fun setPresets(c: Context, v: String) = p(c).edit().putString("presets", v).apply()

    /** raw access for presets and backup */
    fun all(c: Context): Map<String, *> = p(c).all
    fun editor(c: Context): android.content.SharedPreferences.Editor = p(c).edit()

    // cache
    /** album art cache cap in MB, 0 = unlimited (~80 KB a cover, so 75 MB is ~900 covers) */
    fun artCapMb(c: Context) = i(c, "artCap", 75)
    fun setArtCapMb(c: Context, v: Int) = setI(c, "artCap", v)
    /** lyrics cache cap in MB, 0 = unlimited (~4 KB a song, so 10 MB is ~2500 songs) */
    fun lyrCapMb(c: Context) = i(c, "lyrCap", 10)
    fun setLyrCapMb(c: Context, v: Int) = setI(c, "lyrCap", v)
    /** days before a "no lyrics found" song is searched again; 0 = never */
    fun notFoundDays(c: Context) = i(c, "nfDays", 7)
    fun setNotFoundDays(c: Context, v: Int) = setI(c, "nfDays", v)
}
