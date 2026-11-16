package dev.toast.nothingwidgets

/** Turns messy player metadata (YouTube titles, remaster tags, "- Topic" channels) into something LRCLIB matches. */
object TitleCleaner {
    private val junkWords = "official|video|audio|lyrics?|lyric video|visuali[sz]er|music video|mv|hd|hq|4k|explicit|clean|remaster(ed)?|live|color coded|full album"
    private val bracketJunk = Regex("""\s*[(\[][^)\]]*\b($junkWords)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val feat = Regex("""\s*[(\[]?\s*\b(feat\.?|ft\.?|featuring)\s[^)\]]*[)\]]?""", RegexOption.IGNORE_CASE)
    private val dashTail = Regex("""\s+-\s+([^-]*\b(remaster(ed)?|live|mono|stereo|single|radio edit|bonus|demo|version|mix|edit)\b[^-]*)$""", RegexOption.IGNORE_CASE)
    private val channelSuffix = Regex("""\s*(-\s*topic|vevo|official)\s*$""", RegexOption.IGNORE_CASE)

    fun cleanArtist(a: String): String = a.replace(channelSuffix, "").replace(feat, "").split(",", " & ", " x ", " X ").first().trim()

    fun cleanTitle(t: String): String {
        var s = t
        repeat(3) { s = s.replace(bracketJunk, "") }
        s = s.replace(feat, "").replace(dashTail, "")
        return s.replace(Regex("\\s+"), " ").trim().trim('-', '|', ' ')
    }

    /** Returns cleaned (title, artist). "Artist - Title" titles are split when the artist field looks like a channel. */
    fun clean(title: String, artist: String): Pair<String, String> {
        var t = cleanTitle(title)
        var a = cleanArtist(artist)
        val channel = artist.isBlank() || channelSuffix.containsMatchIn(artist)
        val parts = t.split(" - ", limit = 2)
        if (parts.size == 2 && (channel || parts[0].equals(a, true) || a.isBlank())) {
            a = cleanArtist(parts[0]); t = cleanTitle(parts[1])
        }
        return t to a
    }
}
