package dev.toast.nothingwidgets

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Gives the Artwork widget real swipes. App widgets only ever get taps, so this service finds the widget on the
 * home screen (by its `art_root` view id) and puts an invisible accessibility-overlay window exactly over it,
 * only while the launcher is the top window there. The overlay ([SwipeOverlay]) does the gestures and the drag
 * animation. Anything that could move the widget (page scroll, app drawer, shade, another app) hides it first
 * and re-locates once things settle.
 */
class GestureService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var overlay: SwipeOverlay
    private var attached = false
    private val shownAt = Rect()
    private var launcher: String? = null
    private var suppressUntil = 0L
    private val tmp = Rect()
    private val locateR = Runnable { locate() }
    private var pending = false
    /** safety net while showing: catches moves that sent no event we listen to */
    private val poll = object : Runnable { override fun run() { if (attached) { locate(); main.postDelayed(this, 1000) } } }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        launcher = null   // learned from the window that hosts the widget
        overlay = SwipeOverlay(this)
        schedule(0, true)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        // until the launcher is known (first sighting of the widget), any app window event may be it
        val fromLauncher = launcher == null || e.packageName == launcher
        when (e.eventType) {
            // page swipe / scroll in the launcher: the widget is moving, get out of the way now
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (fromLauncher) { hide(); schedule(250, true) }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> schedule(120, true)
            // the widget itself redraws often (lyrics), so content changes never push an already-queued check back
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (fromLauncher) schedule(300, false)
        }
    }

    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }
    private fun cleanup() { instance = null; main.removeCallbacksAndMessages(null); hide(force = true) }

    fun relocateSoon() = schedule(200, true)

    /** A finger went down somewhere else on screen: the widget may be about to move. */
    fun outsideTouch() { hide(); schedule(450, true) }

    private fun schedule(delay: Long, force: Boolean) {
        if (pending && !force) return
        main.removeCallbacks(locateR); pending = true
        main.postDelayed(locateR, delay)
    }

    private fun locate() {
        pending = false
        if (overlay.busy) { schedule(300, false); return }
        val now = SystemClock.uptimeMillis()
        if (now < suppressUntil) { hide(); schedule(suppressUntil - now + 50, true); return }
        val r = findWidget()
        dbg("locate -> $r attached=$attached")
        if (r == null) hide() else show(r)
    }

    /** Screen rect of a visible Artwork widget whose spot is not covered by another window, else null. */
    private fun findWidget(): Rect? {
        val wins = try { windows } catch (e: Exception) { dbg("windows fail $e"); return null }
        dbg("launcher=$launcher wins=" + wins.joinToString { "${it.type}/${it.layer}/${it.root?.packageName}" })
        // the launcher is whichever app window hosts our widget (package lookup of HOME is hidden from us since Android 11)
        var home: AccessibilityWindowInfo? = null
        var node: android.view.accessibility.AccessibilityNodeInfo? = null
        for (w in wins) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = w.root ?: continue
            if (launcher != null && root.packageName != launcher) continue
            val n = findById(root, "$packageName:id/art_root", 0) ?: continue
            home = w; node = n; launcher = root.packageName?.toString(); break
        }
        dbg("found home=${home != null}")
        if (home == null || node == null) return null
        val r = Rect(); node.getBoundsInScreen(r)
        if (r.width() < 40 || r.height() < 40) return null
        // anything above the launcher over that spot (shade, dialogs, keyboard, PiP, another app) wins
        for (w in wins.sortedByDescending { it.layer }) {
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            if (w.id == home.id) break
            w.getBoundsInScreen(tmp)
            if (Rect.intersects(tmp, r)) return null
        }
        return r
    }

    /**
     * Depth-first search by view id. findAccessibilityNodeInfosByViewId can't be used: it resolves the id with the
     * launcher's resources, and our id only exists in ours. Only descends into visible nodes.
     */
    private fun findById(n: android.view.accessibility.AccessibilityNodeInfo, id: String, depth: Int): android.view.accessibility.AccessibilityNodeInfo? {
        if (!n.isVisibleToUser || depth > 30) return null
        if (n.viewIdResourceName == id) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findById(c, id, depth + 1)?.let { return it }
        }
        return null
    }

    private fun show(r: Rect) {
        if (attached && r == shownAt) return
        val lp = (overlay.layoutParams as? WindowManager.LayoutParams) ?: WindowManager.LayoutParams(
            r.width(), r.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // touches elsewhere (start of a page swipe, opening the drawer) arrive as ACTION_OUTSIDE: step aside at once
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "nw-swipe"
        }
        lp.x = r.left; lp.y = r.top; lp.width = r.width(); lp.height = r.height()
        try {
            if (attached) wm.updateViewLayout(overlay, lp) else { wm.addView(overlay, lp); attached = true; main.postDelayed(poll, 1000) }
            shownAt.set(r)
        } catch (e: Exception) { attached = false; dbg("show fail $e") }
    }

    private fun hide(force: Boolean = false) {
        if (!attached || (overlay.busy && !force)) return
        try { wm.removeView(overlay) } catch (_: Exception) {}
        attached = false; main.removeCallbacks(poll)
    }

    /** Long-press belongs to the launcher (move / resize / remove): step aside and replay it there. */
    fun passLongPress(x: Float, y: Float) {
        overlay.reset(); hide(force = true)
        suppressUntil = SystemClock.uptimeMillis() + 1500
        main.postDelayed({
            val p = Path().apply { moveTo(x, y) }
            dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 800)).build(), null, null)
        }, 80)
        schedule(1600, true)
    }

    private fun dbg(s: String) {
        if (!DEBUG) return
        try { java.io.File(cacheDir, "gesture.txt").appendText("${SystemClock.uptimeMillis()} $s\n") } catch (_: Exception) {}
    }

    companion object {
        /** true: trace locate() to cache/gesture.txt (adb shell run-as dev.toast.nothingwidgets cat cache/gesture.txt) */
        private const val DEBUG = false
        @Volatile var instance: GestureService? = null
        fun enabled(c: android.content.Context): Boolean =
            android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains("${c.packageName}/") == true
    }
}
