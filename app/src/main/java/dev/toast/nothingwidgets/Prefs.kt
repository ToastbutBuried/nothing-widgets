package dev.toast.nothingwidgets

import android.content.Context

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("nw", Context.MODE_PRIVATE)
    fun red(c: Context) = p(c).getBoolean("red", false)
    fun setRed(c: Context, v: Boolean) = p(c).edit().putBoolean("red", v).apply()
    /** sensitivity multiplier 0.5..4 */
    fun gain(c: Context) = p(c).getFloat("gain", 1.5f)
    fun setGain(c: Context, v: Float) = p(c).edit().putFloat("gain", v).apply()
}
