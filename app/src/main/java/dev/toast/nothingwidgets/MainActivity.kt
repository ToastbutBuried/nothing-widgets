package dev.toast.nothingwidgets

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val tf = resources.getFont(R.font.doto_bold)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(48, 96, 48, 48) }
        fun tv(t: String, size: Float, c: Int = Renderers.WHITE) = TextView(this).apply { text = t; textSize = size; setTextColor(c); typeface = tf }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; typeface = tf; setOnClickListener { f() } }
        root.addView(tv("● NOTHING WIDGETS", 20f))
        val preview = PreviewView(this)
        status = tv("", 14f, Renderers.DIM).apply { setPadding(0, 32, 0, 32) }
        root.addView(status)
        root.addView(btn("1. Notification access (lyrics)") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        root.addView(btn("2. Audio permission + notifications (visualizer)") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        })
        root.addView(btn("3. Start / stop visualizer") { toggleViz() })
        root.addView(Switch(this).apply {
            text = "Red visualizer"; typeface = tf; setTextColor(Renderers.WHITE); isChecked = Prefs.red(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setRed(this@MainActivity, v); preview.invalidate(); sendBroadcast(Intent(this@MainActivity, VizWidgetProvider::class.java).setAction("android.appwidget.action.APPWIDGET_UPDATE").putExtra("appWidgetIds", android.appwidget.AppWidgetManager.getInstance(this@MainActivity).getAppWidgetIds(ComponentName(this@MainActivity, VizWidgetProvider::class.java)))) }
        })

        root.addView(preview, LinearLayout.LayoutParams(-1, 700))
        root.addView(Switch(this).apply {
            text = "Bars style"; typeface = tf; setTextColor(Renderers.WHITE); isChecked = Prefs.bars(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setBars(this@MainActivity, v); preview.invalidate() }
        })
        root.addView(tv("Sensitivity", 14f).apply { setPadding(0, 24, 0, 0) })
        root.addView(SeekBar(this).apply {
            max = 100; progress = ((Prefs.gain(this@MainActivity) - 0.5f) / 3.5f * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { Prefs.setGain(this@MainActivity, 0.5f + p / 100f * 3.5f) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })
        root.addView(tv("\nLong-press the home screen > Widgets > add Lyrics and Visualizer. Tap the visualizer widget to start or stop it.", 12f, Renderers.DIM))
        if (intent.getBooleanExtra("viz", false)) toggleViz()
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root) })
    }

    private fun toggleViz() {
        if (VisualizerService.active) startService(Intent(this, VisualizerService::class.java).setAction(VisualizerService.ACTION_STOP))
        else startForegroundService(Intent(this, VisualizerService::class.java))
        status.postDelayed({ refresh() }, 500)
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) { refresh() }

    private fun refresh() {
        val notif = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val np = MediaListenerService.nowPlaying()
        status.text = "Notification access: ${if (notif) "ON" else "OFF"}\nAudio permission: ${if (mic) "ON" else "OFF"}\nNow playing: ${if (np.isEmpty()) "-" else np}\nVisualizer: ${if (VisualizerService.active) "running" else "stopped"}"
    }
}

/** Live demo of the 8x8 grid using the current colour and sensitivity. */
class PreviewView(ctx: android.content.Context) : android.view.View(ctx) {
    private val cava = Cava()
    private val raw = FloatArray(8)
    private var last = android.os.SystemClock.elapsedRealtime()
    private var nextBeat = 0L
    private val rnd = java.util.Random()
    override fun onDraw(c: android.graphics.Canvas) {
        val now = android.os.SystemClock.elapsedRealtime()
        val dt = ((now - last) / 1000f).coerceIn(0.005f, 0.2f); last = now
        if (now >= nextBeat) { // fake music: beats with random band energy
            for (i in 0 until 8) raw[i] = (rnd.nextFloat() * (1f - i * .06f)).coerceIn(0f, 1f) * 0.8f
            nextBeat = now + 160 + rnd.nextInt(260)
        }
        for (i in 0 until 8) raw[i] *= 0.9f
        val bars = Prefs.bars(context)
        val lv = if (bars) cava.process(raw, dt, Prefs.gain(context) / 1.5f) else FloatArray(8) { (raw[it] * Prefs.gain(context) / 1.5f).coerceIn(0f, 1f) }
        val s = minOf(width, height)
        c.drawBitmap(Renderers.viz(s, s, lv, Prefs.red(context), false, bars), (width - s) / 2f, 0f, null)
        postInvalidateOnAnimation()
    }
}
