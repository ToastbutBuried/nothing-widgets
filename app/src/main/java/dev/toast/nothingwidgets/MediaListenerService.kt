package dev.toast.nothingwidgets

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
    private var lines: List<LrcLine> = emptyList()
    private var trackKey = ""
    private var status: String? = "NO MUSIC"
    private var screenOn = true

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { redraw() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { loadTrack(); redraw() }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }
    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action == Intent.ACTION_SCREEN_ON
            if (screenOn) redraw()
        }
    }
    private val tick = object : Runnable { override fun run() { redraw() } }

    override fun onListenerConnected() {
        instance = this
        msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val cn = ComponentName(this, MediaListenerService::class.java)
        msm.addOnActiveSessionsChangedListener(sessionsListener, cn)
        pick(msm.getActiveSessions(cn))
        registerReceiver(screenRx, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
    }

    override fun onListenerDisconnected() { cleanup() }
    override fun onDestroy() { cleanup(); super.onDestroy() }
    private fun cleanup() {
        instance = null
        main.removeCallbacks(tick)
        try { unregisterReceiver(screenRx) } catch (_: Exception) {}
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controller?.unregisterCallback(cb); controller = null
    }

    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(cb)
            controller = best
            best?.registerCallback(cb)
        }
        loadTrack(); redraw()
    }

    private fun loadTrack() {
        val md = controller?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        if (title.isEmpty()) { trackKey = ""; lines = emptyList(); status = "NO MUSIC"; return }
        val k = "$title|$artist"
        if (k == trackKey) return
        trackKey = k; lines = emptyList(); status = "SEARCHING…"
        val dur = (md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) / 1000
        io.execute {
            val lrc = LrclibClient.fetch(this, title, artist, dur)
            main.post {
                if (trackKey != k) return@post
                lines = if (lrc.isNullOrEmpty()) emptyList() else LrcParser.parse(lrc)
                status = if (lrc == null) "OFFLINE" else if (lines.isEmpty()) "NO LYRICS" else null
                redraw()
            }
        }
    }

    private fun position(): Double {
        val s = controller?.playbackState ?: return 0.0
        var p = s.position.toDouble()
        if (s.state == PlaybackState.STATE_PLAYING) {
            p += (SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed
        }
        return p / 1000.0
    }

    fun redraw() {
        main.removeCallbacks(tick)
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, LyricsWidgetProvider::class.java))
        if (ids.isEmpty() || !screenOn) return
        val pos = position()
        for (id in ids) {
            val (w, h, d) = Renderers.sizePx(this, id, 250, 180)
            Renderers.push(this, Renderers.lyrics(this, w, h, d, lines, pos, status), intArrayOf(id))
        }
        if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) main.postDelayed(tick, 1000)
    }

    companion object {
        @Volatile var instance: MediaListenerService? = null
        fun nowPlaying(): String = instance?.trackKey?.replace("|", " – ") ?: ""
    }
}
