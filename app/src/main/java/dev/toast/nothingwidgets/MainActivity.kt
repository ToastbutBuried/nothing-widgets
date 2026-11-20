package dev.toast.nothingwidgets

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

/** Settings screen, styled like the widgets: black cards, dot-matrix headings, pill controls. */
class MainActivity : Activity() {
    private companion object {
        const val REQ_EXPORT = 7
        const val REQ_IMPORT_LRC = 8
        const val REQ_BACKUP_OUT = 9
        const val REQ_BACKUP_IN = 10
        /** scroll position to restore after recreate() (presets, restore), so the screen doesn't jump to the top */
        var keepScroll = -1
        /** cap stops in MB; 0 = unlimited, always the far right */
        val ART_CAPS = listOf(10, 25, 50, 75, 100, 150, 200, 300, 500, 0)
        val LYR_CAPS = listOf(1, 2, 5, 10, 25, 50, 100, 0)
    }
    private val LABEL = 0xFF8A8A8A.toInt()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var dotoB: Typeface
    private lateinit var doto: Typeface
    private val mono = Typeface.create("monospace", Typeface.NORMAL)

    // live views
    private lateinit var notifPill: TextView
    private lateinit var audioPill: TextView
    private lateinit var setupHint: TextView
    private lateinit var lyrState: TextView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var latValue: TextView
    private lateinit var latBar: SeekBar
    private lateinit var lyricControls: List<View>
    private lateinit var vizState: TextView
    private lateinit var swipeState: TextView
    private lateinit var swipePill: TextView
    private lateinit var radiusValue: TextView
    private lateinit var vizButton: TextView
    private lateinit var sensValue: TextView
    private lateinit var preview: PreviewView
    private lateinit var saveBtn: TextView
    private lateinit var cacheState: TextView
    private lateinit var artUse: TextView
    private lateinit var lyrUse: TextView
    private lateinit var savedInfo: TextView
    private lateinit var latLabel: TextView
    private lateinit var appsBox: LinearLayout
    private lateinit var langPill: TextView
    private var pendingExport: String? = null
    private var scroller: ScrollView? = null
    private var ticks = 0
    private var dragging = false

    private val tick = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 1000) } }

    // ---------- small view kit ----------
    private fun dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()
    private fun shape(fill: Int, stroke: Int = 0, radius: Float = 99f) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun ripple(bg: GradientDrawable) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)

    private fun text(t: String, size: Float, color: Int, face: Typeface = mono) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); typeface = face; includeFontPadding = false
    }

    private fun label(t: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(View(context).apply { background = shape(Renderers.RED) }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(8) })
        addView(text(t.uppercase(), 11f, LABEL, dotoB).apply { letterSpacing = 0.18f })
    }

    private fun header(t: String, right: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(label(t), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(right.apply { letterSpacing = 0.12f })
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(0xFF0B0B0B.toInt(), 0xFF222222.toInt(), 28f)
        setPadding(dp(20), dp(18), dp(20), dp(20))
    }

    private fun pill(t: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = t.uppercase(); textSize = 12f; typeface = dotoB; letterSpacing = 0.1f; gravity = Gravity.CENTER
        setPadding(dp(16), dp(11), dp(16), dp(11))
        stylePill(this, primary)
        setOnClickListener { onClick() }
    }

    private fun stylePill(v: TextView, active: Boolean, activeColor: Int = Renderers.RED) {
        v.background = ripple(if (active) shape(activeColor) else shape(Color.BLACK, 0xFF444444.toInt()))
        v.setTextColor(if (active) Color.BLACK else Renderers.WHITE)
    }

    private fun row(vararg views: View, gap: Int = 8) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(gap) }) }
    }

    /** Segmented control: one pill per option, the selected one filled. */
    private fun segmented(options: List<String>, selected: Int, color: Int = Renderers.WHITE, onSelect: (Int) -> Unit): LinearLayout {
        val pills = ArrayList<TextView>()
        options.forEachIndexed { i, o ->
            pills.add(pill(o) {
                pills.forEachIndexed { k, p -> stylePill(p, k == i, color) }
                onSelect(i)
            })
        }
        pills.forEachIndexed { k, p -> stylePill(p, k == selected, color) }
        return row(*pills.toTypedArray(), gap = 6)
    }

    /** Segmented control wrapped onto rows of [perRow]; a short last row keeps the same pill width. */
    private fun segmentedGrid(options: List<String>, selected: Int, perRow: Int, color: Int = Renderers.WHITE,
                              onLong: ((Int) -> Unit)? = null, onSelect: (Int) -> Unit): LinearLayout {
        val pills = ArrayList<TextView>()
        options.forEachIndexed { i, o ->
            pills.add(pill(o) { pills.forEachIndexed { k, p -> stylePill(p, k == i, color) }; onSelect(i) }.apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                if (onLong != null) setOnLongClickListener { onLong(i); true }
            })
        }
        pills.forEachIndexed { k, p -> stylePill(p, k == selected, color) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            pills.chunked(perRow).forEachIndexed { r, chunk ->
                val cells: List<View> = chunk + List(perRow - chunk.size) { View(context) }
                if (r > 0) addView(space(6))
                addView(row(*cells.toTypedArray(), gap = 6))
            }
        }
    }

    private fun onOff(v: Boolean) = if (v) 0 else 1

    private fun slider(max: Int, value: Int, onChange: (Int, Boolean) -> Unit) = SeekBar(this).apply {
        this.max = max; progress = value
        progressTintList = ColorStateList.valueOf(Renderers.RED)
        progressBackgroundTintList = ColorStateList.valueOf(0xFF333333.toInt())
        thumbTintList = ColorStateList.valueOf(Renderers.WHITE)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) = onChange(p, u)
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) { dragging = false }
        })
    }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    // ---------- screen ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        dotoB = resources.getFont(R.font.doto_bold); doto = resources.getFont(R.font.doto_regular)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(28), dp(16), dp(32)) }
        fun add(v: View, gapAfter: Int = 14) { root.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)); root.addView(space(gapAfter)) }

        // header
        add(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(8), dp(4), 0)
            addView(text("NOTHING", 34f, Renderers.WHITE, dotoB))
            addView(text("WIDGETS", 34f, Renderers.RED, dotoB))
            addView(space(8))
            addView(text("lyrics · artwork · visualizer", 12f, Renderers.DIM))
        }, 20)

        // setup
        notifPill = pill("…") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        audioPill = pill("…") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1) }
        setupHint = text("", 11f, Renderers.DIM)
        add(card().apply {
            addView(label("Setup")); addView(space(14))
            addView(permRow("Notification access", "reads what's playing", notifPill)); addView(space(10))
            addView(permRow("Audio", "taps the phone's output", audioPill)); addView(space(10))
            addView(setupHint)
        })

        // lyrics
        lyrState = text("", 11f, LABEL, dotoB)
        trackTitle = text("-", 22f, Renderers.WHITE, dotoB).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f) }
        trackArtist = text("", 13f, 0xFF9A9A9A.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        latValue = text("0 ms", 26f, Renderers.WHITE, dotoB)
        latBar = slider(200, 100) { p, user -> val ms = (p - 100) * 50; latValue.text = latText(ms); if (user) setLatency(ms) }
        val minus = pill("−") { nudgeLatency(-50) }; val plus = pill("+") { nudgeLatency(50) }
        val refreshBtn = pill("Refresh") {
            val s = svc() ?: return@pill
            if (s.refreshLyrics()) toast("Searching again…") else toast("These lyrics are saved. Unsave them first.")
        }
        saveBtn = pill("Save") { toggleSave() }
        val exportBtn = pill("Export") { exportLyrics() }
        val importBtn = pill("Import") { importLyrics() }
        val albumBtn = pill("For album") {
            val s = svc() ?: return@pill
            if (s.latencyForAll(true)) toast("${latText(s.latencyMs)} for every song of ${s.album()} without its own value") else toast("This song has no album info")
        }
        val artistBtn = pill("For artist") {
            val s = svc() ?: return@pill
            if (s.latencyForAll(false)) toast("${latText(s.latencyMs)} for every song by ${s.artist()} without its own value") else toast("No artist info")
        }
        val pickBtn = pill("Pick lyrics", primary = true) { listFinds() }
        val searchBtn = pill("Search manually") { manualSearch() }
        val resetBtn = pill("Reset") { latBar.progress = 100; setLatency(0) }
        lyricControls = listOf(refreshBtn, pickBtn, searchBtn, saveBtn, exportBtn, importBtn, minus, plus, resetBtn, latBar, albumBtn, artistBtn)
        latLabel = text("LATENCY · THIS SONG", 10f, LABEL, dotoB).apply { letterSpacing = .18f }
        add(card().apply {
            addView(header("Lyrics", lyrState)); addView(space(16))
            addView(text("NOW PLAYING", 10f, LABEL, dotoB).apply { letterSpacing = .18f }); addView(space(6))
            addView(trackTitle); addView(space(4)); addView(trackArtist); addView(space(16))
            addView(row(refreshBtn, pickBtn)); addView(space(8)); addView(searchBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)); addView(space(8))
            addView(row(saveBtn, exportBtn, importBtn, gap = 6)); addView(space(8))
            addView(text("Save keeps these lyrics for good, also offline and after clearing the cache. Picked and imported lyrics are saved automatically. Import takes a .lrc or plain .txt file.", 11f, Renderers.DIM)); addView(space(22))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(latLabel); addView(space(6))
                    addView(latValue)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(resetBtn)
            }); addView(space(10))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(minus, LinearLayout.LayoutParams(dp(48), dp(40)))
                addView(latBar, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(plus, LinearLayout.LayoutParams(dp(48), dp(40)))
            }); addView(space(10))
            addView(row(albumBtn, artistBtn)); addView(space(8))
            addView(text("+ shows lyrics earlier, − later. Saved per song. \"For album / artist\" makes it the default for songs without their own value; when two songs of an album agree, the rest of it gets their timing as a guess.", 11f, Renderers.DIM))
        })

        // look: presets + accent
        add(lookCard())

        // lyrics widget look
        fun lyrChanged() { MediaListenerService.instance?.redraw() ?: LyricsWidgetProvider.draw(this,
            AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, LyricsWidgetProvider::class.java))) }
        add(card().apply {
            addView(label("Lyrics widget")); addView(space(16))
            addView(sub("TEXT SIZE")); addView(segmented(listOf("S", "M", "L"), Prefs.lyrSize(context)) { Prefs.setLyrSize(context, it); lyrChanged() }); addView(space(14))
            addView(sub("ACCENT")); addView(segmented(listOf("Red", "Mono"), if (Prefs.lyrRed(context)) 0 else 1) { Prefs.setLyrRed(context, it == 0); lyrChanged() }); addView(space(14))
            addView(sub("CONTEXT LINES")); addView(segmented(listOf("On", "Off"), if (Prefs.lyrContext(context)) 0 else 1) { Prefs.setLyrContext(context, it == 0); lyrChanged() }); addView(space(14))
            addView(sub("PLAYER BUTTONS")); addView(segmented(listOf("On", "Off"), if (Prefs.lyrControls(context)) 0 else 1) { Prefs.setLyrControls(context, it == 0); lyrChanged() }); addView(space(14))
            addView(sub("TAP TO SEEK")); addView(segmented(listOf("On", "Off"), if (Prefs.lyrSeek(context)) 0 else 1) { Prefs.setLyrSeek(context, it == 0); lyrChanged() }); addView(space(8))
            addView(text("Tap a lyric line to jump to it (synced lyrics only), anywhere else to open this screen; ⏮ ⏯ ⏭ sit bottom right. Word-by-word fill shows when LRCLIB has word timings.", 11f, Renderers.DIM)); addView(space(18))
            addView(sub("SECOND LINE"))
            addView(segmentedGrid(listOf("Off", "Translate", "Romanize", "Both"), Prefs.trMode(context), 2) {
                Prefs.setTrMode(context, it); MediaListenerService.instance?.subsChanged() }); addView(space(10))
            langPill = pill("") { pickLanguage() }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(text("TRANSLATE TO", 10f, LABEL, dotoB).apply { letterSpacing = .18f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(langPill)
            }); addView(space(8))
            addView(text("Shown small under the current line, only for songs not already in that language (romanize: non-Latin scripts). Uses Google Translate: the lyric lines are sent once per song, then cached.", 11f, Renderers.DIM)); addView(space(18))
            addView(sub("LOCK SCREEN LINE")); addView(segmented(listOf("On", "Off"), onOff(Prefs.lockLyrics(context))) {
                Prefs.setLockLyrics(context, it == 0)
                if (it == 0 && android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                MediaListenerService.instance?.redraw() }); addView(space(8))
            addView(text("The current line as a silent notification, so it shows on the lock screen while music plays. Whether it reaches the always-on display depends on Nothing OS's lock screen settings.", 11f, Renderers.DIM))
            if (lockScreenHidesNotifications()) {
                addView(space(8))
                addView(text("Your lock screen hides notifications, so the line will only show in the notification shade. Turn them on in Settings › Notifications › Lock screen.", 11f, Renderers.RED))
            }
        })

        // artwork widget
        swipeState = text("", 11f, LABEL, dotoB)
        swipePill = pill("Enable swipes", primary = true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Installed apps › Nothing Widgets swipes › On")
        }
        add(card().apply {
            addView(header("Artwork widget", swipeState)); addView(space(14))
            addView(text("Cover art. Swipe left: next track, right: previous. Swipe up: lyrics, down: back to the cover. Tap: pause / play. Long-press still moves the widget.", 12f, Renderers.WHITE).apply { setLineSpacing(0f, 1.25f) })
            addView(space(16))
            radiusValue = text("${Prefs.artRadius(context)} dp", 12f, Renderers.WHITE, dotoB)
            addView(header("Corner rounding", radiusValue)); addView(space(4))
            addView(slider(40, Prefs.artRadius(context)) { p, user ->
                if (!user) return@slider
                Prefs.setArtRadius(context, p); radiusValue.text = "$p dp"
                MediaListenerService.instance?.artChanged() ?: ArtWidgetProvider.draw(context, ArtWidgetProvider.ids(context))
            }); addView(space(4))
            addView(text("19 dp matches Nothing's own widgets.", 11f, Renderers.DIM)); addView(space(16))
            addView(sub("PROGRESS DOTS")); addView(segmented(listOf("On", "Off"), onOff(Prefs.artProgress(context))) {
                Prefs.setArtProgress(context, it == 0); MediaListenerService.instance?.artChanged() }); addView(space(16))
            addView(swipePill, LinearLayout.LayoutParams(MATCH_PARENT, dp(48))); addView(space(8))
            addView(text("Widgets can't receive swipes on their own, so an accessibility service lays an invisible touch layer over this one widget while the home screen shows. Without it a tap still pauses.", 11f, Renderers.DIM))
            tileButton(this, ArtTile::class.java, "Artwork lyrics")
        })

        // visualizer
        vizState = text("", 11f, LABEL, dotoB)
        preview = PreviewView(this)
        sensValue = text("", 12f, Renderers.WHITE, dotoB)
        vizButton = pill("Start", primary = true) { toggleViz() }
        add(card().apply {
            addView(header("Visualizer", vizState)); addView(space(16))
            addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, dp(240))); addView(space(18))
            addView(text("STYLE", 10f, LABEL, dotoB).apply { letterSpacing = .18f }); addView(space(8))
            addView(segmentedGrid(listOf("Dots", "Bars", "Mirror", "Wave", "Ring"), Prefs.vizStyle(context), 3) { Prefs.setVizStyle(context, it); widgetsChanged() }); addView(space(14))
            addView(text("COLOUR", 10f, LABEL, dotoB).apply { letterSpacing = .18f }); addView(space(8))
            addView(segmented(listOf("White", "Accent"), if (Prefs.red(context)) 1 else 0) { Prefs.setRed(context, it == 1); widgetsChanged() }); addView(space(14))
            addView(sub("BANDS")); val bandOpts = listOf(0, 8, 12, 16)
            addView(segmented(listOf("Auto", "8", "12", "16"), bandOpts.indexOf(Prefs.bands(context)).coerceAtLeast(0)) { Prefs.setBands(context, bandOpts[it]); widgetsChanged() }); addView(space(14))
            addView(sub("PEAK CAPS · BARS ONLY")); addView(segmented(listOf("On", "Off"), if (Prefs.peaks(context)) 0 else 1) { Prefs.setPeaks(context, it == 0); widgetsChanged() }); addView(space(16))
            addView(header("Sensitivity", sensValue)); addView(space(4))
            addView(slider(100, ((Prefs.gain(context) - 0.5f) / 3.5f * 100).toInt()) { p, _ ->
                val g = 0.5f + p / 100f * 3.5f; Prefs.setGain(context, g); sensValue.text = "%.1f×".format(g)
            }); addView(space(16))
            addView(vizButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
            tileButton(this, VizTile::class.java, "Visualizer")
        })

        // apps
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(card().apply {
            addView(label("Apps")); addView(space(14))
            addView(appsBox); addView(space(8))
            addView(text("Ignored apps never take over the widgets, e.g. a podcast app or YouTube videos. Apps show up here once they've played something.", 11f, Renderers.DIM))
        })
        fillApps()
        sensValue.text = "%.1f×".format(Prefs.gain(this))

        // cache (housekeeping, so last)
        cacheState = text("", 11f, LABEL, dotoB)
        artUse = text("", 11f, Renderers.DIM); lyrUse = text("", 11f, Renderers.DIM)
        savedInfo = text("", 11f, Renderers.DIM)
        fun capChanged() { MediaListenerService.instance?.cacheChanged(false) ?: Thread { Caches.trim(this) }.start(); updateCache() }
        val nfDays = listOf(1, 3, 7, 30, 0)
        add(card().apply {
            addView(header("Cache", cacheState)); addView(space(18))
            capSlider(this, "COVERS", ART_CAPS, 75, Prefs.artCapMb(context), artUse) { Prefs.setArtCapMb(context, it); capChanged() }
            addView(space(18))
            capSlider(this, "LYRICS", LYR_CAPS, 10, Prefs.lyrCapMb(context), lyrUse) { Prefs.setLyrCapMb(context, it); capChanged() }
            addView(space(20))
            addView(sub("RETRY \"NO LYRICS\" AFTER · DAYS"))
            addView(segmented(nfDays.map { if (it == 0) "Off" else "$it" }, nfDays.indexOf(Prefs.notFoundDays(context)).coerceAtLeast(2)) { Prefs.setNotFoundDays(context, nfDays[it]) })
            addView(space(18))
            addView(pill("Clear cache") { clearCache() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48))); addView(space(8))
            addView(savedInfo); addView(space(4))
            addView(text("Covers and lyrics of songs you played, so they show instantly and offline. When a cap is full the least recently played go first.", 11f, Renderers.DIM))
        })

        // backup
        add(card().apply {
            addView(label("Backup")); addView(space(14))
            addView(row(pill("Export") { backupOut() }, pill("Import") { backupIn() })); addView(space(8))
            addView(text("One file with every setting, per-song latency and your saved lyrics, e.g. before reinstalling. Covers and cached lyrics refill on their own.", 11f, Renderers.DIM))
        })

        // footer
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        add(text("Add the widgets: long-press the home screen › Widgets › Nothing Widgets. All three resize; tap the visualizer to start or stop it. Quick Settings tiles: Visualizer and Artwork lyrics.\n\nv$ver",
            11f, Renderers.DIM).apply { setPadding(dp(4), 0, dp(4), 0); setLineSpacing(0f, 1.3f) }, 0)

        setContentView(ScrollView(this).apply {
            scroller = this
            if (keepScroll >= 0) { val y = keepScroll; keepScroll = -1; post { scrollTo(0, y) } }
            setBackgroundColor(Color.BLACK); isVerticalScrollBarEnabled = false; clipToPadding = true; addView(root)
            // targetSdk 35 draws edge-to-edge: keep content clear of the status and navigation bars
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom); insets
            }
        })
        if (intent.getBooleanExtra("viz", false)) toggleViz()
    }

    private fun capText(mb: Int, rec: Int) = (if (mb == 0) "UNLIMITED" else "$mb MB") + if (mb == rec) " · RECOMMENDED" else ""

    /** Label + live value, a stepped slider (far right = unlimited) and the usage line under it. Saves on release only,
     *  so dragging past a small cap doesn't evict anything. */
    private fun capSlider(card: LinearLayout, name: String, stops: List<Int>, rec: Int, cur: Int, usage: TextView, onSet: (Int) -> Unit) {
        val value = text(capText(cur, rec), 12f, Renderers.WHITE, dotoB)
        val start = stops.indexOf(cur).let { if (it < 0) stops.indexOf(rec) else it }
        val sb = SeekBar(this).apply {
            max = stops.size - 1; progress = start
            progressTintList = ColorStateList.valueOf(Renderers.RED)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF333333.toInt())
            thumbTintList = ColorStateList.valueOf(Renderers.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { value.text = capText(stops[p], rec) }
                override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar) { dragging = false; onSet(stops[s.progress]) }
            })
        }
        card.addView(header2(name, value)); card.addView(space(4))
        card.addView(sb); card.addView(space(4))
        card.addView(usage)
    }

    /** small caps label left, value right */
    private fun header2(t: String, right: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(text(t, 10f, LABEL, dotoB).apply { letterSpacing = .18f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(right)
    }

    private fun sub(t: String) = text(t, 10f, LABEL, dotoB).apply { letterSpacing = .18f; setPadding(0, 0, 0, dp(8)) }

    private fun permRow(name: String, sub: String, pill: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(name, 14f, Renderers.WHITE)); addView(space(3)); addView(text(sub, 11f, Renderers.DIM))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(pill, LinearLayout.LayoutParams(dp(104), dp(40)))
    }

    // ---------- actions ----------
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun latText(ms: Int) = (if (ms > 0) "+" else "") + ms + " ms"

    private fun svc(): MediaListenerService? {
        val s = MediaListenerService.instance
        if (s == null || !s.hasTrack()) { toast("Play a song first (needs notification access)"); return null }
        return s
    }

    private fun setLatency(ms: Int) { MediaListenerService.instance?.setLatency(ms) }
    private fun nudgeLatency(d: Int) {
        val ms = ((latBar.progress - 100) * 50 + d).coerceIn(-5000, 5000)
        latBar.progress = ms / 50 + 100; setLatency(ms)
    }

    private fun listFinds() {
        val s = svc() ?: return
        toast("Searching LRCLIB…")
        s.findCandidates { showCandidates(s, it) }
    }

    private fun showCandidates(s: MediaListenerService, list: List<Candidate>?) {
        when {
            list == null -> toast("LRCLIB unreachable")
            list.isEmpty() -> toast("No synced lyrics found")
            else -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Pick lyrics")
                .setItems(list.map { it.label() }.toTypedArray()) { _, i -> s.choose(list[i]); toast("Using: ${list[i].track}") }
                .setNegativeButton("Cancel", null).show()
        }
    }

    /** Type your own title/artist when the player's metadata is too messy to match. */
    private fun manualSearch() {
        val s = svc() ?: return
        val (ct, ca) = TitleCleaner.clean(s.title(), s.artist())
        fun field(hint: String, v: String) = EditText(this).apply { this.hint = hint; setText(v); setSingleLine(); setTextColor(Renderers.WHITE); setHintTextColor(Renderers.DIM); typeface = mono }
        val t = field("Title", ct); val a = field("Artist", ca)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(t); addView(a) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Search LRCLIB").setView(box)
            .setPositiveButton("Search") { _, _ ->
                toast("Searching…")
                s.findCandidates(t.text.toString().trim(), a.text.toString().trim()) { showCandidates(s, it) }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun toggleSave() {
        val s = svc() ?: return
        if (!s.isSaved()) { if (s.saveLyrics()) toast("Saved") else toast("No lyrics to save"); refresh(); return }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Unsave lyrics?")
            .setMessage("Goes back to the automatic LRCLIB match for this song.")
            .setPositiveButton("Unsave") { _, _ -> s.unsaveLyrics(); refresh() }.setNegativeButton("Cancel", null).show()
    }

    private fun exportLyrics() {
        val s = svc() ?: return
        val (name, body) = s.exportLyrics() ?: run { toast("No lyrics to export"); return }
        pendingExport = body
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (name.endsWith(".txt")) "text/plain" else "application/octet-stream").putExtra(Intent.EXTRA_TITLE, name)
        try { startActivityForResult(i, REQ_EXPORT) } catch (_: Exception) { toast("No file picker on this phone") }
    }

    @Suppress("OVERRIDE_DEPRECATION")   // the androidx result API would be the only dependency
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req == REQ_EXPORT) { val body = pendingExport; pendingExport = null; if (body != null && res == RESULT_OK && uri != null) writeTo(uri, body, "Exported") }
        if (res != RESULT_OK || uri == null) return
        when (req) {
            REQ_BACKUP_OUT -> Thread { val b = Backup.export(this); ui.post { writeTo(uri, b, "Backup saved") } }.start()
            REQ_IMPORT_LRC -> {
                val t = readFrom(uri) ?: return
                val s = MediaListenerService.instance
                if (s != null && s.importLyrics(t)) { toast("Imported and saved"); refresh() } else toast("Nothing to import")
            }
            REQ_BACKUP_IN -> {
                val t = readFrom(uri) ?: return
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Restore backup?")
                    .setMessage("Replaces all settings, per-song latency and saved lyrics on this phone.")
                    .setPositiveButton("Restore") { _, _ ->
                        try {
                            val n = Backup.import(this, t)
                            toast("Restored · $n saved songs")
                            lookChanged(); MediaListenerService.instance?.reloadAll()
                        } catch (e: Exception) { toast("Not a Nothing Widgets backup") }
                    }.setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun writeTo(uri: android.net.Uri, body: String, ok: String) {
        try { contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }; toast(ok) } catch (_: Exception) { toast("Couldn't write the file") }
    }

    /** text files up to 2 MB */
    private fun readFrom(uri: android.net.Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { s -> String(s.readNBytesCompat(2 shl 20)) }
    } catch (_: Exception) { toast("Couldn't read the file"); null }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var n = 0
        while (n < max) { val r = read(buf); if (r < 0) break; out.write(buf, 0, r); n += r }
        return out.toByteArray()
    }

    private fun openDoc(req: Int, vararg types: String) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, types)
        try { startActivityForResult(i, req) } catch (_: Exception) { toast("No file picker on this phone") }
    }

    private fun importLyrics() { svc() ?: return; openDoc(REQ_IMPORT_LRC, "text/*", "application/octet-stream") }

    private fun backupOut() {
        val name = "nothing-widgets-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".json"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, name)
        try { startActivityForResult(i, REQ_BACKUP_OUT) } catch (_: Exception) { toast("No file picker on this phone") }
    }

    private fun backupIn() = openDoc(REQ_BACKUP_IN, "application/json", "application/octet-stream", "text/*")

    // ---------- look ----------
    private fun lookCard(): LinearLayout = card().apply {
        addView(label("Look")); addView(space(14))
        val all = Presets.BUILT_IN + Presets.saved(context)
        val own = Presets.BUILT_IN.size
        addView(sub("PRESETS"))
        addView(segmentedGrid(all.map { it.first }, all.indexOfFirst { Presets.matches(context, it.second) }, 2,
            onLong = { i ->
                if (i < own) return@segmentedGrid
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Delete \"${all[i].first}\"?")
                    .setPositiveButton("Delete") { _, _ -> Presets.delete(context, all[i].first); keepScroll = scroller?.scrollY ?: -1; recreate() }.setNegativeButton("Cancel", null).show()
            }) { i -> Presets.apply(context, all[i].second); lookChanged() })
        addView(space(10))
        addView(pill("Save current look") { saveLook() }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)); addView(space(16))
        addView(sub("ACCENT COLOUR"))
        addView(segmented(listOf("Red", "From cover"), if (Prefs.accentCover(context)) 1 else 0) {
            Prefs.setAccentCover(context, it == 1); lookChanged(recreate = false) }); addView(space(8))
        addView(text("A preset sets every widget's look at once. Long-press your own preset to delete it. \"From cover\" takes the most vivid colour of the album art for the dots, sung words and the visualizer (when its colour is Accent).", 11f, Renderers.DIM))
    }

    private fun saveLook() {
        val f = EditText(this).apply { hint = "Name"; setSingleLine(); setTextColor(Renderers.WHITE); setHintTextColor(Renderers.DIM); typeface = mono }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Save look").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = f.text.toString().trim().take(16)
                if (n.isEmpty() || Presets.BUILT_IN.any { it.first.equals(n, true) }) { toast("Pick another name"); return@setPositiveButton }
                Presets.save(this, n); keepScroll = scroller?.scrollY ?: -1; recreate()
            }.setNegativeButton("Cancel", null).show()
    }

    /** a preset, the accent or a restore changed several settings: redraw every widget, rebuild this screen */
    private fun lookChanged(recreate: Boolean = true) {
        MediaListenerService.instance?.accentChanged() ?: run {
            LyricsWidgetProvider.draw(this, AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, LyricsWidgetProvider::class.java)))
            ArtWidgetProvider.draw(this, ArtWidgetProvider.ids(this))
        }
        widgetsChanged()
        if (recreate) { keepScroll = scroller?.scrollY ?: -1; recreate() }
    }

    // ---------- apps ----------
    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun fillApps() {
        appsBox.removeAllViews()
        val ignored = Prefs.ignoredApps(this)
        val apps = Prefs.seenApps(this).filter { it != packageName }.map { it to appName(it) }.sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) { appsBox.addView(text("No media apps seen yet. Play something.", 12f, Renderers.WHITE)); return }
        apps.forEachIndexed { i, (pkg, name) ->
            if (i > 0) appsBox.addView(space(10))
            val on = pkg !in ignored
            val state = pill(if (on) "Shown" else "Ignored") {
                val cur = Prefs.ignoredApps(this)
                Prefs.setIgnoredApps(this, if (pkg in cur) cur - pkg else cur + pkg)
                fillApps(); MediaListenerService.instance?.reloadAll()
            }
            stylePill(state, !on, Renderers.WHITE)
            appsBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(name, 14f, if (on) Renderers.WHITE else Renderers.DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(space(3)); addView(text(pkg, 10f, Renderers.DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(state, LinearLayout.LayoutParams(dp(112), dp(40)))
            })
        }
    }

    // ---------- translation language ----------
    private fun langName(code: String) = Translator.LANGS.firstOrNull { it.first == code }?.second
        ?: java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.forLanguageTag(code)).ifEmpty { code }

    private fun pickLanguage() {
        val phone = java.util.Locale.getDefault().language
        val items = listOf("" to "Phone language · ${langName(phone)}") + Translator.LANGS
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Translate to")
            .setItems(items.map { it.second }.toTypedArray()) { _, i ->
                Prefs.setTrLang(this, items[i].first); langPill.text = langName(Prefs.trLang(this)).uppercase()
                MediaListenerService.instance?.subsChanged()
            }.setNegativeButton("Cancel", null).show()
    }

    /** Settings.Secure lock_screen_show_notifications = 0 means no notification reaches the lock screen */
    private fun lockScreenHidesNotifications(): Boolean = try {
        Settings.Secure.getInt(contentResolver, "lock_screen_show_notifications", 1) == 0
    } catch (_: Exception) { false }

    // ---------- quick settings ----------
    /** Android 13+: one tap asks to add the tile; older: say where to find it */
    private fun tileButton(card: LinearLayout, cls: Class<*>, name: String) {
        card.addView(space(10))
        if (android.os.Build.VERSION.SDK_INT < 33) {
            card.addView(text("Quick Settings tile \"$name\": edit your tiles to add it.", 11f, Renderers.DIM)); return
        }
        card.addView(pill("Add Quick Settings tile") {
            val sbm = getSystemService(android.app.StatusBarManager::class.java)
            sbm.requestAddTileService(ComponentName(this, cls), name, android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat), mainExecutor) { r ->
                when (r) {
                    android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> toast("Tile added")
                    android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> toast("Already in Quick Settings")
                    android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {}
                    else -> toast("Add it by editing your Quick Settings tiles")
                }
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun clearCache() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Clear cache?")
            .setMessage("Removes cached covers and lyrics. Saved lyrics stay.")
            .setPositiveButton("Clear") { _, _ ->
                Thread { Caches.clear(this); ui.post { MediaListenerService.instance?.cacheChanged(true) ?: Caches.clearMem(); updateCache(); toast("Cache cleared") } }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun mb(b: Long) = when {
        b < DiskCache.MB -> "%.0f KB".format(b / 1024.0)
        b % DiskCache.MB == 0L -> "${b / DiskCache.MB} MB"
        else -> "%.1f MB".format(b / DiskCache.MB.toDouble())
    }
    private fun plural(n: Int, one: String) = "$n $one" + if (n == 1) "" else "s"

    /** reads the cache folders off the main thread */
    private fun updateCache() = Thread {
        val st = Caches.stats(this)
        val artCap = Prefs.artCapMb(this) * DiskCache.MB; val lyrCap = Prefs.lyrCapMb(this) * DiskCache.MB   // 0 = unlimited
        ui.post {
            if (isFinishing) return@post
            fun use(n: Int, one: String, b: Long, cap: Long) = "${plural(n, one)} · ${mb(b)} used" +
                if (cap > 0 && b * 100 >= cap) " · ${(b * 100 / cap).coerceAtMost(100)}%" else ""
            artUse.text = use(st.artCount, "cover", st.artBytes, artCap)
            lyrUse.text = use(st.lyrCount, "song", st.lyrBytes, lyrCap)
            savedInfo.text = "${plural(st.saved, "saved song")} · not in the caps, never cleared"
            cacheState.text = mb(st.artBytes + st.lyrBytes)
        }
    }.start()

    private fun toggleViz() {
        if (VisualizerService.active) startService(Intent(this, VisualizerService::class.java).setAction(VisualizerService.ACTION_STOP))
        else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { toast("Grant audio first"); return }
        else startForegroundService(Intent(this, VisualizerService::class.java))
        ui.postDelayed({ refresh() }, 400)
    }

    private fun widgetsChanged() {
        preview.invalidate()
        if (!VisualizerService.active) VizWidgetProvider.drawIdle(this)
    }

    // ---------- live state ----------
    override fun onResume() { super.onResume(); MediaListenerService.ensureBound(this); ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }
    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) = refresh()

    private fun refresh() {
        if (ticks++ % 5 == 0) updateCache()
        val notif = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        for ((p, ok) in listOf(notifPill to notif, audioPill to audio)) {
            p.text = if (ok) "● ON" else "GRANT"; p.isEnabled = !ok
            if (ok) { p.background = shape(0xFF161616.toInt()); p.setTextColor(0xFF9A9A9A.toInt()) } else stylePill(p, true)
        }
        setupHint.text = if (notif && audio) "All set." else "Greyed out? App info › ⋮ › Allow restricted settings, then try again."

        val s = MediaListenerService.instance
        val has = s?.hasTrack() == true
        val np = MediaListenerService.nowPlaying()
        val parts = np.split(" – ", limit = 2)
        trackTitle.text = if (has) parts[0] else "Nothing playing"
        trackArtist.text = if (has) parts.getOrElse(1) { "" } else if (!notif) "grant notification access first" else "start music in any app"
        lyrState.text = if (has) s!!.lyricInfo() else "IDLE"
        lyricControls.forEach { it.isEnabled = has; it.alpha = if (has) 1f else .35f }
        latLabel.text = "LATENCY · " + when (s?.latencySrc) {
            LatencyStore.Src.ALBUM -> "ALBUM DEFAULT"; LatencyStore.Src.ARTIST -> "ARTIST DEFAULT"
            LatencyStore.Src.GUESS -> "GUESSED FROM ALBUM"; else -> "THIS SONG"
        }
        if (langPill.text.isEmpty()) langPill.text = langName(Prefs.trLang(this)).uppercase()
        val sv = has && s!!.isSaved()
        saveBtn.text = if (sv) "● SAVED" else "SAVE"; stylePill(saveBtn, sv)
        if (!dragging) { val ms = s?.latencyMs ?: 0; latBar.progress = ms / 50 + 100; latValue.text = latText(ms) }

        val sw = GestureService.enabled(this)
        swipeState.text = if (sw) "SWIPES ON" else "SWIPES OFF"
        swipeState.setTextColor(if (sw) Renderers.RED else LABEL)
        swipePill.visibility = if (sw) View.GONE else View.VISIBLE

        vizState.text = when {
            VisualizerService.failed -> "UNSUPPORTED"
            VisualizerService.active -> "LIVE"
            VisualizerService.stopReason == "IDLE" -> "STOPPED · IDLE"
            VisualizerService.stopReason == "NO WIDGET" -> "STOPPED · NO WIDGET"
            else -> "STOPPED"
        }
        vizState.setTextColor(if (VisualizerService.active) Renderers.RED else LABEL)
        vizButton.text = if (VisualizerService.active) "STOP" else "START"
        stylePill(vizButton, !VisualizerService.active)
    }
}
