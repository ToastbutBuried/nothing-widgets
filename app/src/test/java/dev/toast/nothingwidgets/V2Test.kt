package dev.toast.nothingwidgets

import org.junit.Assert.*
import org.junit.Test

class TitleCleanerTest {
    @Test fun stripsYoutubeJunk() {
        assertEquals("Creep" to "Radiohead", TitleCleaner.clean("Radiohead - Creep (Official Music Video)", "Radiohead - Topic"))
        assertEquals("Blinding Lights" to "The Weeknd", TitleCleaner.clean("Blinding Lights [Official Audio]", "TheWeekndVEVO".let { "The Weeknd" }))
        assertEquals("Here Comes The Sun", TitleCleaner.cleanTitle("Here Comes The Sun - Remastered 2009"))
        assertEquals("Stay", TitleCleaner.cleanTitle("Stay (feat. Justin Bieber)"))
        assertEquals("Stay", TitleCleaner.cleanTitle("Stay ft. Justin Bieber"))
        assertEquals("Daft Punk", TitleCleaner.cleanArtist("Daft Punk, Pharrell Williams"))
    }
    @Test fun leavesNormalTitlesAlone() {
        assertEquals("Mr. Brightside" to "The Killers", TitleCleaner.clean("Mr. Brightside", "The Killers"))
        assertEquals("Live Forever", TitleCleaner.cleanTitle("Live Forever"))
    }
}

class EnhancedLrcTest {
    @Test fun parsesWordTimings() {
        val l = LrcParser.parse("[00:10.00]<00:10.00>Hello <00:10.50>bright <00:11.00>world\n[00:12.00]next")
        assertEquals(2, l.size)
        assertEquals("Hello bright world", l[0].s)
        assertEquals(3, l[0].words.size)
        assertEquals("bright", l[0].s.substring(l[0].words[1].start, l[0].words[1].end).trim())
        assertTrue(l[1].words.isEmpty())
        assertEquals(0f, LrcParser.sungChars(l[0], l[1], 9.9), 0.01f)
        val mid = LrcParser.sungChars(l[0], l[1], 10.75)
        assertTrue(mid > 6f && mid < 13f)
        assertEquals(l[1].s.length.toFloat(), LrcParser.sungChars(l[1], null, 12.5), 0.01f)
    }
    @Test fun plainSpreadsOverSong() {
        val l = LrcParser.plain("a\n\nb\nc", 100.0)
        assertEquals(3, l.size); assertTrue(l[0].t < l[1].t && l[2].t < 100)
    }
    @Test fun binaryIndexMatchesLinear() {
        val l = (0 until 50).map { LrcLine(it * 2.0, "$it") }
        for (p in listOf(-1.0, 0.0, 1.9, 2.0, 57.3, 200.0)) {
            var lin = -1; for (k in l.indices) if (l[k].t <= p) lin = k else break
            assertEquals(lin, LrcParser.index(l, p))
        }
    }
}

class CavaPeaksTest {
    @Test fun capsHoldThenFall() {
        val c = Cava(8)
        c.process(FloatArray(8) { 0.8f }, 0.03f, 1f)
        val top = c.caps[0]
        c.process(FloatArray(8), 0.05f, 1f)
        assertEquals(top, c.caps[0], 0.0001f)          // still holding
        repeat(10) { c.process(FloatArray(8), 0.05f, 1f) }
        assertTrue(c.caps[0] < top)                     // fell after the hold
        assertTrue(c.caps[0] >= c.shaped[0])            // never below the bar
    }
    @Test fun gridFollowsShape() {
        assertEquals(8 to 8, Grid.of(0, 300, 300))
        assertEquals(16 to 8, Grid.of(0, 600, 300))
        assertEquals(12 to 6, Grid.of(12, 600, 300))
    }
}
