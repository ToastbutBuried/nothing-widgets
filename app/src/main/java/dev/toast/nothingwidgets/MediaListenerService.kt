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

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { redraw() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { loadTrack(); redraw() }
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
        loadTrack(); redraw()
    }

    private fun loadTrack() {
        val md = controller?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        if (title.isEmpty()) { trackKey = ""; lines = emptyList(); status = "NO MUSIC"; latencyMs = 0; return }
        val k = "$title|$artist"
        if (k == trackKey) return
        trackKey = k; lines = emptyList(); status = "SEARCHING…"; shownIdx = -2
        val dur = (md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) / 1000
        curTitle = title; curArtist = artist; curDur = dur
        latencyMs = LatencyStore.get(this, k)   // each song keeps its own saved latency
        io.execute {
            val lrc = LrclibClient.fetch(this, title, artist, dur)
            main.post { if (trackKey == k) apply(lrc) }
        }
    }

    private fun apply(lrc: String?) {
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

    // ---- used by the settings screen ----
    fun refreshLyrics() { LrclibClient.clear(this, curTitle, curArtist); trackKey = ""; shownIdx = -2; loadTrack(); redraw() }

    /** Manual search when title/artist given, else the current track's metadata. */
    fun findCandidates(title: String? = null, artist: String? = null, cb: (List<Candidate>?) -> Unit) {
        val t = title ?: curTitle; val a = artist ?: curArtist
        io.execute { val r = LrclibClient.search(t, a); main.post { cb(r?.synced) } }
    }

    fun choose(c: Candidate) { LrclibClient.saveChoice(this, curTitle, curArtist, c.synced); apply(c.synced) }

    fun setLatency(ms: Int) { latencyMs = ms; LatencyStore.set(this, trackKey, ms); shownIdx = -2; redraw() }
    fun hasTrack() = trackKey.isNotEmpty()
    fun lyricInfo(): String = status?.let { if (it == "PLAIN") "PLAIN · ${lines.size} LINES" else it }
        ?: ("${lines.size} LINES" + if (lines.any { it.words.isNotEmpty() }) " · WORDS" else "")
    fun title() = curTitle
    fun artist() = curArtist
    fun sizeChanged(id: Int) { sizes.remove(id); redraw() }

    private var shownIdx = -2
    private var prevIdx = -2
    private var animStart = 0L

    fun redraw() {
        main.removeCallbacks(tick)
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, LyricsWidgetProvider::class.java))
        if (ids.isEmpty() || !screenOn) return
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
            val bmp = Renderers.lyrics(this, surfaces.getOrPut(id) { Surface() }, w, h, d, lines, pos, status, prevIdx, t, st)
            Renderers.push(this, bmp, intArrayOf(id), R.layout.widget_lyrics) { LyricsWidgetProvider.wire(this, it, st.controls) }
        }
        if (!playing()) return
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
