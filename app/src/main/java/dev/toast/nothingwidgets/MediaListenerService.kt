package dev.toast.nothingwidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.*
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import java.util.concurrent.Executors

/** Reads now-playing from any media app, fetches lyrics, and redraws the lyrics widget while playing. */
class MediaListenerService : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var msm: MediaSessionManager
    private var controller: MediaController? = null
    /** callbacks on every active session, so a different app starting playback takes over */
    private val watched = HashMap<MediaSession_Token, MediaController.Callback>()
    private var lines: List<LrcLine> = emptyList()
    private var trackKey = ""
    private var status: String? = "NO MUSIC"
    private var screenOn = true
    private var curTitle = ""
    private var curArtist = ""
    private var curDur = 0L
    @Volatile var latencyMs = 0
        private set

    // per-widget caches: pixel size and reusable bitmap
    private val sizes = HashMap<Int, Triple<Int, Int, Float>>()
    private val surfaces = HashMap<Int, Surface>()
    private val hits = ArrayList<LineHit>(5)
    /** what each artwork widget last showed in cover mode, so it is only pushed when something changed */
    private val artSig = HashMap<Int, String>()

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { redraw() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { loadTrack(); loadArt(); redraw() }
        override fun onSessionDestroyed() { main.post { pick(currentSessions()) } }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }
    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action == Intent.ACTION_SCREEN_ON
            if (screenOn) redraw()
        }
    }
    private val tick = Runnable { redraw() }

    override fun onListenerConnected() {
        instance = this
        msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.addOnActiveSessionsChangedListener(sessionsListener, cn())
        pick(currentSessions())
        registerReceiver(screenRx, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
    }

    override fun onListenerDisconnected() { cleanup(); requestRebind(cn()) }
    override fun onDestroy() { cleanup(); super.onDestroy() }
    private fun cleanup() {
        instance = null
        main.removeCallbacks(tick)
        try { unregisterReceiver(screenRx) } catch (_: Exception) {}
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controller?.unregisterCallback(cb); controller = null
        unwatchAll()
    }

    private fun cn() = ComponentName(this, MediaListenerService::class.java)
    private fun currentSessions(): List<MediaController> = try { msm.getActiveSessions(cn()) } catch (_: Exception) { emptyList() }

    private fun unwatchAll() { /* controllers drop with their sessions; we only keep weak listeners */ watched.clear() }

    /** Prefer the session that is playing; keep the current one when nothing plays. Watch all others for takeover. */
    private fun pick(list: List<MediaController>?) {
        val all = list ?: emptyList()
        val playing = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val keep = all.firstOrNull { it.sessionToken == controller?.sessionToken }
        val best = playing ?: keep ?: all.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(cb)
            controller = best
            best?.registerCallback(cb, main)
        }
        for (c in all) if (c.sessionToken != best?.sessionToken && !watched.containsKey(c.sessionToken)) {
            val w = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (state?.state == PlaybackState.STATE_PLAYING) main.post { pick(currentSessions()) }
                }
            }
            watched[c.sessionToken] = w; c.registerCallback(w, main)
        }
        loadTrack(); loadArt(); redraw()
    }

    // ---- album art (artwork widget) ----
    private var art: android.graphics.Bitmap? = null
    private var artSrc: Any? = null
    /** track the shown cover belongs to (art can come from the cache when the player sends none) */
    private var artFor = ""
    /** bumps whenever the track or its cover changes; the swipe overlay waits on it after a skip */
    @Volatile var trackVer = 0
        private set

    private fun loadArt() {
        val md = controller?.metadata
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = if (bmp != null) null else (md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI))
        val src: Any? = bmp ?: uri
        val k = trackKey
        if (src == artSrc && k == artFor) return
        artSrc = src; artFor = k
        trackVer++; artSig.clear()
        if (bmp != null) {
            art = bmp
            if (k.isNotEmpty()) { Caches.remember(k, bmp); io.execute { Caches.artPut(this, k, bmp) } }
            return
        }
        // no bitmap in the metadata: show the cached cover now, then load the URI (if any) and refresh the cache
        art = if (k.isEmpty()) null else Caches.artMem(k)
        if (k.isEmpty()) return
        val haveMem = art != null
        fun show(b: android.graphics.Bitmap) = main.post {
            if (artSrc == src && artFor == k) { Caches.remember(k, b); art = b; trackVer++; artSig.clear(); redraw() }
        }
        io.execute {
            if (!haveMem) Caches.artDisk(this, k)?.let { show(it) }
            if (uri == null) return@execute
            val b = try {
                contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null }
            if (b != null) { Caches.artPut(this, k, b); show(b) }
        }
    }

    private fun loadTrack() {
        val md = controller?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        if (title.isEmpty()) {
            if (trackKey.isNotEmpty()) trackVer++
            trackKey = ""; lines = emptyList(); status = "NO MUSIC"; latencyMs = 0; curTitle = ""; curArtist = ""; curLrc = null; saved = false; return
        }
        val k = "$title|$artist"
        if (k == trackKey) return
        trackKey = k; lines = emptyList(); status = "SEARCHING…"; shownIdx = -2; trackVer++
        val dur = (md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) / 1000
        curTitle = title; curArtist = artist; curDur = dur
        latencyMs = LatencyStore.get(this, k)   // each song keeps its own saved latency
        saved = LrclibClient.isSaved(this, title, artist)
        lrcMem.get(k)?.let { apply(it); return }
        io.execute {
            val lrc = LrclibClient.fetch(this, title, artist, dur)
            main.post { if (trackKey == k) { if (!lrc.isNullOrEmpty()) lrcMem.put(k, lrc); apply(lrc) } }
        }
    }

    /** raw lyrics of the last few songs (never "not found"/offline, those must be able to retry) */
    private val lrcMem = android.util.LruCache<String, String>(16)
    /** raw text of what is shown now: lrc, PLAIN-prefixed text, "" (none) or null (offline) */
    private var curLrc: String? = null
    private var saved = false

    private fun apply(lrc: String?) {
        curLrc = lrc
        if (lrc != null && lrc.startsWith(LrclibClient.PLAIN)) {
            lines = LrcParser.plain(lrc.removePrefix(LrclibClient.PLAIN), curDur.toDouble())
            status = if (lines.isEmpty()) "NO LYRICS" else "PLAIN"
        } else {
            lines = if (lrc.isNullOrEmpty()) emptyList() else LrcParser.parse(lrc)
            status = if (lrc == null) "OFFLINE" else if (lines.isEmpty()) "NO LYRICS" else null
        }
        shownIdx = -2; redraw()
    }

    private fun playing() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun position(): Double {
        val s = controller?.playbackState ?: return 0.0
        var p = s.position.toDouble()
        if (s.state == PlaybackState.STATE_PLAYING) p += (SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed
        return p / 1000.0 + latencyMs / 1000.0
    }

    // ---- widget buttons ----
    fun transport(action: String) {
        val t = controller?.transportControls ?: return
        when (action) {
            LyricsWidgetProvider.ACT_PREV -> t.skipToPrevious()
            LyricsWidgetProvider.ACT_NEXT -> t.skipToNext()
            LyricsWidgetProvider.ACT_PLAY -> if (playing()) t.pause() else t.play()
        }
    }

    /** Tap-to-seek: [lineMs] is the line's start in lyric time, so take the latency back off. Ignored if the song changed. */
    fun seekToLine(lineMs: Long, track: Int) {
        if (lineMs < 0 || track != trackKey.hashCode() || trackKey.isEmpty()) return
        controller?.transportControls?.seekTo((lineMs - latencyMs).coerceAtLeast(0))
        shownIdx = -2   // snap to the new line instead of sliding
    }

    // ---- used by the settings screen ----
    /** Searches LRCLIB again for this song. false = it has saved lyrics, which win over any search (unsave first). */
    fun refreshLyrics(): Boolean {
        if (saved) return false
        val k = trackKey
        io.execute { LrclibClient.clearCached(this, curTitle, curArtist); main.post { if (trackKey == k) reload() } }
        return true
    }

    private fun reload() { lrcMem.remove(trackKey); trackKey = ""; shownIdx = -2; loadTrack(); redraw() }

    fun isSaved() = saved
    /** Keeps the current lyrics for good: outside the cache cap, never searched again. false = nothing to save. */
    fun saveLyrics(): Boolean {
        val l = curLrc ?: return false
        if (l.isEmpty() || !hasTrack()) return false
        LrclibClient.saveChoice(this, curTitle, curArtist, l); saved = true
        return true
    }
    /** Back to the cached / searched match. */
    fun unsaveLyrics() { LrclibClient.unsave(this, curTitle, curArtist); saved = false; reload() }

    /** file name + content for exporting the current lyrics; synced as .lrc (with title/artist tags), plain as .txt */
    fun exportLyrics(): Pair<String, String>? {
        val l = curLrc?.takeIf { it.isNotEmpty() && hasTrack() } ?: return null
        val base = (if (curArtist.isNotEmpty()) "$curArtist - $curTitle" else curTitle).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
        if (l.startsWith(LrclibClient.PLAIN)) return "$base.txt" to l.removePrefix(LrclibClient.PLAIN)
        val tags = if (l.contains("[ti:")) "" else "[ti:$curTitle]\n[ar:$curArtist]\n"
        return "$base.lrc" to tags + l
    }

    /** settings changed a cap or cleared the cache */
    fun cacheChanged(cleared: Boolean) {
        if (cleared) { lrcMem.evictAll(); Caches.clearMem() }
        io.execute { Caches.trim(this) }
    }

    /** Manual search when title/artist given, else the current track's metadata. */
    fun findCandidates(title: String? = null, artist: String? = null, cb: (List<Candidate>?) -> Unit) {
        val t = title ?: curTitle; val a = artist ?: curArtist
        io.execute { val r = LrclibClient.search(t, a); main.post { cb(r?.synced) } }
    }

    fun choose(c: Candidate) { LrclibClient.saveChoice(this, curTitle, curArtist, c.synced); saved = true; lrcMem.put(trackKey, c.synced); apply(c.synced) }

    fun setLatency(ms: Int) { latencyMs = ms; LatencyStore.set(this, trackKey, ms); shownIdx = -2; redraw() }
    fun hasTrack() = trackKey.isNotEmpty()
    fun lyricInfo(): String = (if (saved) "SAVED · " else "") + (status?.let { if (it == "PLAIN") "PLAIN · ${lines.size} LINES" else it }
        ?: ("${lines.size} LINES" + if (lines.any { it.words.isNotEmpty() }) " · WORDS" else ""))
    fun title() = curTitle
    fun artist() = curArtist
    fun sizeChanged(id: Int) { sizes.remove(id); artSig.remove(id); redraw() }
    fun isPlaying() = playing()

    /** Artwork widget: 0 = cover, 1 = lyrics. Saved, and every artwork widget redraws. */
    fun setArtMode(m: Int) { Prefs.setArtMode(this, m); artSig.clear(); redraw() }
    /** a look setting of the artwork widget changed (e.g. corner radius): redraw it */
    fun artChanged() { artSig.clear(); redraw() }

    // frames for the swipe overlay, drawn at the overlay's own size (one surface per side so both can slide at once)
    private val overlaySurf = arrayOf(Surface(), Surface())
    fun artFrame(mode: Int, w: Int, h: Int, d: Float): android.graphics.Bitmap {
        val s = overlaySurf[mode.coerceIn(0, 1)]
        return if (mode == 1) Renderers.lyrics(this, s, w, h, d, lines, position(), status, st = LyricsStyle.from(this, playing()).copy(controls = false, radius = Prefs.artRadius(this).toFloat()))
        else Renderers.art(this, s, w, h, d, art, curTitle, curArtist, playing(), Prefs.lyrRed(this))
    }

    private var shownIdx = -2
    private var prevIdx = -2
    private var animStart = 0L

    fun redraw() {
        main.removeCallbacks(tick)
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, LyricsWidgetProvider::class.java))
        val artIds = ArtWidgetProvider.ids(this)
        val artLyrics = artIds.isNotEmpty() && Prefs.artMode(this) == 1
        if ((ids.isEmpty() && artIds.isEmpty()) || !screenOn) return
        val pos = position()
        val idx = LrcParser.index(lines, pos)
        val now = SystemClock.elapsedRealtime()
        if (idx != shownIdx) {
            // animate only the normal forward step; jumps (seek, new track) snap
            val step = shownIdx >= -1 && idx == shownIdx + 1
            prevIdx = if (step) shownIdx else -2
            animStart = now; shownIdx = idx
        }
        val t = ((now - animStart) / ANIM_MS.toFloat()).coerceIn(0f, 1f)
        val st = LyricsStyle.from(this, playing())
        for (id in ids) {
            val (w, h, d) = sizes.getOrPut(id) { Renderers.sizePx(this, id, 250, 180) }
            // plain lyrics are only spread evenly over the song, so their times mean nothing to seek to
            val seek = st.seek && status == null && lines.isNotEmpty()
            val bmp = Renderers.lyrics(this, surfaces.getOrPut(id) { Surface() }, w, h, d, lines, pos, status, prevIdx, t, st,
                if (seek) hits else null)
            if (!seek) hits.clear()
            Renderers.push(this, bmp, intArrayOf(id), R.layout.widget_lyrics) {
                LyricsWidgetProvider.wire(this, it, st.controls, hits, lines, d, trackKey.hashCode())
            }
        }
        val red = Prefs.lyrRed(this)
        for (id in artIds) {
            val (w, h, d) = sizes.getOrPut(id) { Renderers.sizePx(this, id, 160, 160) }
            val surf = surfaces.getOrPut(id) { Surface() }
            val bmp = if (artLyrics) {
                artSig.remove(id)
                Renderers.lyrics(this, surf, w, h, d, lines, pos, status, prevIdx, t, st.copy(controls = false, radius = Prefs.artRadius(this).toFloat()))
            } else {
                val sig = "$trackVer|${playing()}|$w|$h|$red"
                if (artSig[id] == sig) continue
                artSig[id] = sig
                Renderers.art(this, surf, w, h, d, art, curTitle, curArtist, playing(), red)
            }
            val eq = !artLyrics && playing() && curTitle.isNotEmpty()
            Renderers.push(this, bmp, intArrayOf(id), R.layout.widget_art) { ArtWidgetProvider.wire(this, it, eq) }
        }
        if (!playing()) return
        if (ids.isEmpty() && !artLyrics) return   // cover mode is static: no ticking
        if (t < 1f) { main.postDelayed(tick, 40); return }
        // karaoke lines need a fast tick for the word fill
        if (lines.getOrNull(idx)?.words?.isNotEmpty() == true) { main.postDelayed(tick, 120); return }
        // otherwise wake exactly at the next line, or once a second for the clock/progress bar
        val speed = (controller?.playbackState?.playbackSpeed ?: 1f).coerceAtLeast(0.1f)
        val nextT = lines.getOrNull(idx + 1)?.t
        val untilNext = if (nextT != null) ((nextT - pos) * 1000 / speed).toLong() else 1000L
        main.postDelayed(tick, untilNext.coerceIn(30L, 1000L))
    }

    companion object {
        private const val ANIM_MS = 380L
        @Volatile var instance: MediaListenerService? = null
        fun nowPlaying(): String = instance?.trackKey?.replace("|", " – ") ?: ""

        /** Ask the system to reconnect the listener if access is granted but we're not bound (e.g. after an update). */
        fun ensureBound(c: Context) {
            if (instance != null) return
            val granted = android.provider.Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")?.contains(c.packageName) == true
            if (granted) requestRebind(ComponentName(c, MediaListenerService::class.java))
        }
    }
}

private typealias MediaSession_Token = android.media.session.MediaSession.Token
