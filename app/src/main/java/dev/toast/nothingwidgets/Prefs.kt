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
    /** true = "bars" style (snappy, steep trail), false = original dots */
    fun bars(c: Context) = b(c, "bars", true)
    fun setBars(c: Context, v: Boolean) = setB(c, "bars", v)
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
}
