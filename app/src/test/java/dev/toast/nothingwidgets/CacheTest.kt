package dev.toast.nothingwidgets

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CacheTest {
    private fun cache(cap: Long) = DiskCache(Files.createTempDirectory("nwcache").toFile(), ".bin") { cap }

    @Test fun trimDropsLeastRecentlyUsedFirst() {
        val c = cache(250)
        c.put("a", ByteArray(100)); c.put("b", ByteArray(100))
        c.get("a", now = 1_000_000L); c.get("b", now = 2_000_000L)
        c.put("c", ByteArray(100))           // 300 > 250: oldest (a) goes
        assertNull(c.get("a"))
        assertNotNull(c.get("b")); assertNotNull(c.get("c"))
        assertEquals(2 to 200L, c.stats())
    }

    @Test fun getTouchesMtime() {
        val c = cache(1000)
        c.put("a", ByteArray(10))
        assertEquals(5_000_000L, c.get("a", now = 5_000_000L)!!.lastModified())
    }

    @Test fun clearAndRemove() {
        val c = cache(1000)
        c.put("a", ByteArray(10)); c.put("b", ByteArray(10))
        c.remove("a"); assertNull(c.get("a")); assertEquals(1, c.stats().first)
        c.clear(); assertEquals(0 to 0L, c.stats())
    }

    @Test fun notFoundExpiry() {
        val day = 86_400_000L
        val m = NotFound.marker(10 * day)
        assertTrue(NotFound.isMarker(m)); assertTrue(NotFound.isMarker("")); assertFalse(NotFound.isMarker("[00:01.00]hi"))
        assertFalse(NotFound.expired(m, 16 * day, 7))
        assertTrue(NotFound.expired(m, 18 * day, 7))
        assertFalse(NotFound.expired(m, 1000 * day, 0))   // never
        assertTrue(NotFound.expired("", 0, 7))            // legacy: retried once
        assertTrue(NotFound.expired("", 0, 0))
    }
}

class CacheKeyTest {
    @Test fun keysAreFileSafeAndMatch() {
        val k = DiskCache.key("AC/DC: Back|In Black")
        assertTrue(k.matches(Regex("[0-9a-f]{32}")))
        assertEquals(DiskCache.key("Creep", "Radiohead"), DiskCache.key("Creep|Radiohead"))
    }
}

class TranslatorTest {
    @Test fun splitsKeepLineCount() {
        assertEquals(listOf("tell me your name", "the sky is blue"), Translator.splitTranslated("tell me your name\nthe sky is blue", 2))
        assertNull(Translator.splitTranslated("one line only", 2))
        assertEquals(listOf("Kimi no namae o oshiete", "sora ga aoi"), Translator.splitRoman("Kimi no namae o oshiete | sora ga aoi", 2))
        assertEquals(listOf("a", "b", "c"), Translator.splitRoman("a### b| c".replace("###", "|"), 3))
    }
    @Test fun chunksStayUnderLimit() {
        val lines = List(100) { "x".repeat(50) }
        val ch = Translator.chunks(lines, max = 1000)
        assertEquals(100, ch.sumOf { it.size })
        assertTrue(ch.all { c -> c.sumOf { it.length + 3 } <= 1000 })
        assertEquals(listOf(listOf("a", "b")), Translator.chunks(listOf("a", "b")))
    }
}
