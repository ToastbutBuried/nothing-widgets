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
    private var pendingExport: String? = null
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
        val pickBtn = pill("Pick lyrics", primary = true) { listFinds() }
        val searchBtn = pill("Search manually") { manualSearch() }
        val resetBtn = pill("Reset") { latBar.progress = 100; setLatency(0) }
        lyricControls = listOf(refreshBtn, pickBtn, searchBtn, saveBtn, exportBtn, minus, plus, resetBtn, latBar)
        add(card().apply {
            addView(header("Lyrics", lyrState)); addView(space(16))
            addView(text("NOW PLAYING", 10f, LABEL, dotoB).apply { letterSpacing = .18f }); addView(space(6))
            addView(trackTitle); addView(space(4)); addView(trackArtist); addView(space(16))
            addView(row(refreshBtn, pickBtn)); addView(space(8)); addView(searchBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)); addView(space(8))
            addView(row(saveBtn, exportBtn)); addView(space(8))
            addView(text("Save keeps these lyrics for good, also offline and after clearing the cache. Picked lyrics are saved automatically.", 11f, Renderers.DIM)); addView(space(22))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("LATENCY · THIS SONG", 10f, LABEL, dotoB).apply { letterSpacing = .18f }); addView(space(6))
                    addView(latValue)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(resetBtn)
            }); addView(space(10))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(minus, LinearLayout.LayoutParams(dp(48), dp(40)))
                addView(latBar, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(plus, LinearLayout.LayoutParams(dp(48), dp(40)))
            }); addView(space(8))
            addView(text("+ shows lyrics earlier, − later. Saved per song and reapplied when it plays again.", 11f, Renderers.DIM))
        })

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
            addView(text("Tap a lyric line to jump to it (synced lyrics only), anywhere else to open this screen; ⏮ ⏯ ⏭ sit bottom right. Word-by-word fill shows when LRCLIB has word timings.", 11f, Renderers.DIM))
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
            addView(swipePill, LinearLayout.LayoutParams(MATCH_PARENT, dp(48))); addView(space(8))
            addView(text("Widgets can't receive swipes on their own, so an accessibility service lays an invisible touch layer over this one widget while the home screen shows. Without it a tap still pauses.", 11f, Renderers.DIM))
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
            addView(segmented(listOf("Dots", "Bars"), if (Prefs.bars(context)) 1 else 0) { Prefs.setBars(context, it == 1); widgetsChanged() }); addView(space(14))
            addView(text("COLOUR", 10f, LABEL, dotoB).apply { letterSpacing = .18f }); addView(space(8))
            addView(segmented(listOf("White", "Red"), if (Prefs.red(context)) 1 else 0) { Prefs.setRed(context, it == 1); widgetsChanged() }); addView(space(14))
            addView(sub("BANDS")); val bandOpts = listOf(0, 8, 12, 16)
            addView(segmented(listOf("Auto", "8", "12", "16"), bandOpts.indexOf(Prefs.bands(context)).coerceAtLeast(0)) { Prefs.setBands(context, bandOpts[it]); widgetsChanged() }); addView(space(14))
            addView(sub("PEAK CAPS")); addView(segmented(listOf("On", "Off"), if (Prefs.peaks(context)) 0 else 1) { Prefs.setPeaks(context, it == 0); widgetsChanged() }); addView(space(16))
            addView(header("Sensitivity", sensValue)); addView(space(4))
            addView(slider(100, ((Prefs.gain(context) - 0.5f) / 3.5f * 100).toInt()) { p, _ ->
                val g = 0.5f + p / 100f * 3.5f; Prefs.setGain(context, g); sensValue.text = "%.1f×".format(g)
            }); addView(space(16))
            addView(vizButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        })
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

        // footer
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        add(text("Add the widgets: long-press the home screen › Widgets › Nothing Widgets. Lyrics resizes freely; the visualizer is 2×2, tap it to start or stop. Both widgets resize.\n\nv$ver",
            11f, Renderers.DIM).apply { setPadding(dp(4), 0, dp(4), 0); setLineSpacing(0f, 1.3f) }, 0)

        setContentView(ScrollView(this).apply {
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
        if (req != REQ_EXPORT) return
        val body = pendingExport; pendingExport = null
        val uri = data?.data
        if (res != RESULT_OK || uri == null || body == null) return
        try { contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }; toast("Exported") }
        catch (_: Exception) { toast("Export failed") }
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
