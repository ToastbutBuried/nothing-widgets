package dev.toast.nothingwidgets

/** One word of an enhanced-LRC line: start time and its char range [start, end) in the line text. */
data class LrcWord(val t: Double, val start: Int, val end: Int)

data class LrcLine(val t: Double, val s: String, val words: List<LrcWord> = emptyList())

object LrcParser {
    private val stamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
    private val wordStamp = Regex("""<(\d+):(\d+(?:\.\d+)?)>""")
    private val anyTag = Regex("""\[[^\]]*]""")

    fun parse(src: String): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        for (l in src.lines()) {
            val stamps = stamp.findAll(l).toList()
            if (stamps.isEmpty()) continue
            val body = l.replace(anyTag, "")
            val (txt, words) = words(body)
            for (m in stamps)
                out.add(LrcLine(m.groupValues[1].toDouble() * 60 + m.groupValues[2].toDouble(), txt, words))
        }
        return out.sortedBy { it.t }
    }

    /** Splits "<00:01.00>Hello <00:01.50>world" into plain text + word timings. Lines without word tags get no words. */
    private fun words(body: String): Pair<String, List<LrcWord>> {
        val tags = wordStamp.findAll(body).toList()
        if (tags.isEmpty()) return body.trim() to emptyList()
        val sb = StringBuilder(); val ws = ArrayList<LrcWord>()
        sb.append(body.substring(0, tags[0].range.first))
        for ((k, m) in tags.withIndex()) {
            val textEnd = if (k + 1 < tags.size) tags[k + 1].range.first else body.length
            val piece = body.substring(m.range.last + 1, textEnd)
            val t = m.groupValues[1].toDouble() * 60 + m.groupValues[2].toDouble()
            val start = sb.length; sb.append(piece)
            if (piece.isNotBlank()) ws.add(LrcWord(t, start, sb.length))
        }
        val raw = sb.toString()
        val lead = raw.length - raw.trimStart().length
        val text = raw.trim()
        return text to ws.map { LrcWord(it.t, (it.start - lead).coerceIn(0, text.length), (it.end - lead).coerceIn(0, text.length)) }
    }

    /** index of the last line with t <= pos, or -1 */
    fun index(lines: List<LrcLine>, pos: Double): Int {
        var lo = 0; var hi = lines.size - 1; var i = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (lines[m].t <= pos) { i = m; lo = m + 1 } else hi = m - 1 }
        return i
    }

    /** How many characters of [line] are "sung" at [pos] (fractional), for karaoke fill. */
    fun sungChars(line: LrcLine, next: LrcLine?, pos: Double): Float {
        val w = line.words
        if (w.isEmpty()) return line.s.length.toFloat()
        var k = -1
        for (i in w.indices) if (w[i].t <= pos) k = i else break
        if (k < 0) return 0f
        val wEnd = if (k + 1 < w.size) w[k + 1].t else (next?.t ?: (w[k].t + 1.0)).coerceAtMost(w[k].t + 2.0)
        val f = ((pos - w[k].t) / (wEnd - w[k].t).coerceAtLeast(0.05)).coerceIn(0.0, 1.0)
        return (w[k].start + (w[k].end - w[k].start) * f).toFloat()
    }

    /** Spreads plain (unsynced) lyrics evenly over the song so the widget can still scroll through them. */
    fun plain(text: String, durationSec: Double): List<LrcLine> {
        val ls = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (ls.isEmpty()) return emptyList()
        val dur = if (durationSec > 10) durationSec else 200.0
        val step = dur * 0.92 / ls.size
        return ls.mapIndexed { k, s -> LrcLine(dur * 0.04 + k * step, s) }
    }
}
