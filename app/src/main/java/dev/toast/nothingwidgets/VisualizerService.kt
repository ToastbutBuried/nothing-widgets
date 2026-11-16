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
        running = true; active = true; refresh(true)
        thread = Thread { loop() }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun loop() {
        val rate = 44100
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 2048))
        } catch (e: SecurityException) { stopSelf(); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { stopSelf(); return }
        rec.startRecording()
        val buf = ShortArray(512)
        val levels = FloatArray(8)
        var lastDraw = 0L
        val mgr = AppWidgetManager.getInstance(this)
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            val tgt = Fft.bands(buf)
            for (c in 0 until 8) levels[c] += (tgt[c] - levels[c]) * .3f
            val now = SystemClock.elapsedRealtime()
            if (screenOn && now - lastDraw >= 66) {
                lastDraw = now
                val ids = mgr.getAppWidgetIds(ComponentName(this, VizWidgetProvider::class.java))
                if (ids.isEmpty()) { stopSelf(); break }
                val red = Prefs.red(this)
                for (id in ids) {
                    val (w, h) = Renderers.sizePx(this, id, 180, 180)
                    Renderers.push(this, Renderers.viz(w, h, levels, red, false), intArrayOf(id), VizWidgetProvider.toggleIntent(this))
                }
            }
        }
        rec.stop(); rec.release()
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
            val (w, h) = Renderers.sizePx(this, id, 180, 180)
            Renderers.push(this, Renderers.viz(w, h, FloatArray(8), Prefs.red(this), !on), intArrayOf(id), VizWidgetProvider.toggleIntent(this))
        }
    }

    companion object {
        const val ACTION_STOP = "dev.toast.nothingwidgets.STOP"
        @Volatile var active = false
    }
}
