package dev.toast.nothingwidgets

import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Paint
import android.os.IBinder
import android.os.SystemClock

class VisualizerService : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var screenOn = true
    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { screenOn = i.action != Intent.ACTION_SCREEN_OFF }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopReason = "STOPPED"; stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("viz", "Visualizer", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, VisualizerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "viz").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Visualizer live").setContentText("Tap to stop").setContentIntent(stop).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        registerReceiver(screenRx, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) })
        running = true; active = true; failed = false; stopReason = ""
        thread = Thread({ loop() }, "viz").also { it.start() }
        return START_NOT_STICKY
    }

    /** Things that change rarely: read once a second instead of every frame. */
    private class Config(val ids: IntArray, val w: Int, val h: Int, val cols: Int, val rows: Int,
                         val bars: Boolean, val red: Boolean, val gain: Float, val peaks: Boolean)

    private fun config(mgr: AppWidgetManager): Config {
        val ids = mgr.getAppWidgetIds(ComponentName(this, VizWidgetProvider::class.java))
        val (w, h, _) = if (ids.isNotEmpty()) Renderers.sizePx(this, ids[0], 110, 110, CAP_PX) else Triple(1, 1, 1f)
        val (cols, rows) = Renderers.grid(Prefs.bands(this), w, h)
        return Config(ids, w, h, cols, rows, Prefs.bars(this), Prefs.red(this), Prefs.gain(this), Prefs.peaks(this))
    }

    private fun loop() {
        // Tap the global output mix (audio session 0): reacts to what the phone plays, not the mic.
        val vis = try {
            android.media.audiofx.Visualizer(0).apply {
                val range = android.media.audiofx.Visualizer.getCaptureSizeRange()
                captureSize = 512.coerceIn(range[0], range[1])   // 256 bins: plenty for 16 bands, half the IPC/FFT of 1024
                enabled = true
            }
        } catch (e: Exception) { null }
        if (vis == null) { failed = true; stopReason = "UNSUPPORTED"; stopSelf(); return }
        val fft = ByteArray(vis.captureSize)
        val mgr = AppWidgetManager.getInstance(this)
        val km = getSystemService(KeyguardManager::class.java)
        val surf = Surface(); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val click = VizWidgetProvider.toggleIntent(this)
        var cfg = config(mgr); var cfgAt = SystemClock.elapsedRealtime()
        var cava = Cava(cfg.cols)
        var last = SystemClock.elapsedRealtime()
        var quietSince = 0L
        var fullNext = true
        while (running) {
            val now = SystemClock.elapsedRealtime()
            if (now - cfgAt > 1000) {
                val old = cfg; cfg = config(mgr); cfgAt = now
                if (!old.ids.contentEquals(cfg.ids) || old.w != cfg.w || old.h != cfg.h) fullNext = true
                if (cfg.ids.isEmpty()) { stopReason = "NO WIDGET"; stopSelf(); break }
                if (cava.cols != cfg.cols) cava = Cava(cfg.cols)
            }
            if (!screenOn || km.isKeyguardLocked) { Thread.sleep(300); last = SystemClock.elapsedRealtime(); continue }
            val ok = vis.getFft(fft) == android.media.audiofx.Visualizer.SUCCESS
            val dt = ((now - last) / 1000f).coerceIn(0.005f, 0.2f); last = now
            val raw = if (ok) Fft.bandsFromFft(fft, cfg.cols) else FloatArray(cfg.cols)
            val levels = if (cfg.bars) cava.process(raw, dt, cfg.gain) else FloatArray(cfg.cols) { (raw[it] * cfg.gain).coerceIn(0f, 1f) }
            // battery saver: quiet -> 8 fps after 3 s; silent/paused for 30 s -> stop
            val energy = raw.average().toFloat()
            if (energy > QUIET) quietSince = 0L else if (quietSince == 0L) quietSince = now
            val quietFor = if (quietSince == 0L) 0L else now - quietSince
            if (quietFor > 30_000) { stopReason = "IDLE"; stopSelf(); break }
            val bmp = Renderers.viz(surf, paint, cfg.w, cfg.h, levels, if (cfg.peaks) cava.caps else null, cfg.cols, cfg.rows, cfg.red, false, cfg.bars)
            if (surf.changed) {
                // full update (layout + click) only when needed, then cheap bitmap-only frames
                if (fullNext) { Renderers.push(this, bmp, cfg.ids) { it.setOnClickPendingIntent(R.id.img, click) }; fullNext = false }
                else Renderers.pushFrame(this, bmp, cfg.ids)
            }
            val frame = if (quietFor > 3_000) 125L else 42L   // 24 fps, 8 fps when quiet
            val spent = SystemClock.elapsedRealtime() - now
            if (spent < frame) Thread.sleep(frame - spent)
        }
        vis.enabled = false; vis.release()
    }

    override fun onDestroy() {
        running = false; active = false
        try { unregisterReceiver(screenRx) } catch (_: Exception) {}
        thread?.join(500)
        VizWidgetProvider.drawIdle(this)
        super.onDestroy()
    }

    companion object {
        const val CAP_PX = 300f
        private const val QUIET = 0.03f
        const val ACTION_STOP = "dev.toast.nothingwidgets.STOP"
        @Volatile var active = false
        @Volatile var failed = false
        /** why it last stopped: "", STOPPED, IDLE, NO WIDGET, UNSUPPORTED */
        @Volatile var stopReason = ""
    }
}
