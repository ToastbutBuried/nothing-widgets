package dev.toast.nothingwidgets

data class LrcLine(val t: Double, val s: String)

object LrcParser {
    private val stamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
    fun parse(src: String): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        for (l in src.lines()) {
            val txt = l.replace(Regex("""\[[^\]]*]"""), "").trim()
            for (m in stamp.findAll(l))
                out.add(LrcLine(m.groupValues[1].toDouble() * 60 + m.groupValues[2].toDouble(), txt))
        }
        return out.sortedBy { it.t }
    }
    /** index of the last line with t <= pos, or -1 */
    fun index(lines: List<LrcLine>, pos: Double): Int {
        var i = -1
        for (k in lines.indices) if (lines[k].t <= pos) i = k else break
        return i
    }
}
