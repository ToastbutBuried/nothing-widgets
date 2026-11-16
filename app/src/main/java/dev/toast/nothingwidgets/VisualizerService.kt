package dev.toast.nothingwidgets

import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock

class VisualizerService : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var screenOn = true
    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { screenOn = i.action == Intent.ACTION_SCREEN_ON }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("viz", "Visualizer", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, VisualizerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "viz").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Visualizer listening").setContentText("Tap to stop").setContentIntent(stop).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        registerReceiver(screenRx, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        running = true; active = true; failed = false; refresh(true)
        thread = Thread { loop() }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun loop() {
        // Tap the global output mix (audio session 0): reacts to what the phone plays, not the mic.
        val vis = try {
            android.media.audiofx.Visualizer(0).apply {
                captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1]
                enabled = true
            }
        } catch (e: Exception) { null }
        if (vis == null) { failed = true; stopSelf(); return }
        val fft = ByteArray(vis.captureSize)
        val cava = Cava()
        val mgr = AppWidgetManager.getInstance(this)
        var last = SystemClock.elapsedRealtime()
        while (running) {
            if (!screenOn) { Thread.sleep(250); last = SystemClock.elapsedRealtime(); continue }
            val ok = vis.getFft(fft) == android.media.audiofx.Visualizer.SUCCESS
            val now = SystemClock.elapsedRealtime()
            val dt = ((now - last) / 1000f).coerceIn(0.005f, 0.2f); last = now
            val bars = Prefs.bars(this)
            val raw = if (ok) Fft.bandsFromFft(fft) else FloatArray(8)
            val levels = if (bars) cava.process(raw, dt, Prefs.gain(this)) else FloatArray(8) { (raw[it] * Prefs.gain(this)).coerceIn(0f, 1f) }
            val ids = mgr.getAppWidgetIds(ComponentName(this, VizWidgetProvider::class.java))
            if (ids.isEmpty()) { stopSelf(); break }
            Renderers.push(this, Renderers.viz(VIZ_PX, VIZ_PX, levels, Prefs.red(this), false, bars), ids, VizWidgetProvider.toggleIntent(this))
            val spent = SystemClock.elapsedRealtime() - now
            if (spent < 33) Thread.sleep(33 - spent)   // ~30 fps cap
        }
        vis.enabled = false; vis.release()
    }

    override fun onDestroy() {
        running = false; active = false
        try { unregisterReceiver(screenRx) } catch (_: Exception) {}
        thread?.join(500)
        refresh(false)
        super.onDestroy()
    }

    private fun refresh(on: Boolean) {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, VizWidgetProvider::class.java))
        for (id in ids) {
            val w = VIZ_PX; val h = VIZ_PX
            Renderers.push(this, Renderers.viz(w, h, FloatArray(8), Prefs.red(this), !on, Prefs.bars(this)), intArrayOf(id), VizWidgetProvider.toggleIntent(this))
        }
    }

    companion object {
        const val VIZ_PX = 260
        const val ACTION_STOP = "dev.toast.nothingwidgets.STOP"
        @Volatile var active = false
        @Volatile var failed = false
    }
}
