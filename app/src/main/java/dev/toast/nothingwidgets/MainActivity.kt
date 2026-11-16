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
        status = tv("", 14f, Renderers.DIM).apply { setPadding(0, 32, 0, 32) }
        root.addView(status)
        root.addView(btn("1. Notification access (lyrics)") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        root.addView(btn("2. Microphone + notifications (visualizer)") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        })
        root.addView(Switch(this).apply {
            text = "Red visualizer"; typeface = tf; setTextColor(Renderers.WHITE); isChecked = Prefs.red(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setRed(this@MainActivity, v); sendBroadcast(Intent(this@MainActivity, VizWidgetProvider::class.java).setAction("android.appwidget.action.APPWIDGET_UPDATE").putExtra("appWidgetIds", android.appwidget.AppWidgetManager.getInstance(this@MainActivity).getAppWidgetIds(ComponentName(this@MainActivity, VizWidgetProvider::class.java)))) }
        })
        root.addView(tv("\nLong-press the home screen > Widgets > add Lyrics and Visualizer. Tap the visualizer widget to start or stop it.", 12f, Renderers.DIM))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root) })
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) { refresh() }

    private fun refresh() {
        val notif = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val np = MediaListenerService.nowPlaying()
        status.text = "Notification access: ${if (notif) "ON" else "OFF"}\nMicrophone: ${if (mic) "ON" else "OFF"}\nNow playing: ${if (np.isEmpty()) "-" else np}\nVisualizer: ${if (VisualizerService.active) "running" else "stopped"}"
    }
}
