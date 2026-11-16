package dev.toast.nothingwidgets

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The touch layer over the Artwork widget. Transparent until touched; while dragging it paints the widget itself
 * (frames from [MediaListenerService.artFrame]) so the card follows the finger, like Nothing's own music widget.
 *  - drag right: previous track, drag left: next (⏮/⏭ revealed behind the card)
 *  - drag up: lyrics, drag down: cover (the other side slides in)
 *  - tap: play/pause (dot ▶/❚❚ flash)
 *  - long press: handed back to the launcher
 */
@SuppressLint("ViewConstructor")
class SwipeOverlay(private val svc: GestureService) : View(svc) {
    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(svc).scaledTouchSlop.toFloat()
    private val flingV = ViewConfiguration.get(svc).scaledMinimumFlingVelocity * 6f

    /** true while a gesture or its animation runs: the service must not move or remove us then */
    var busy = false; private set

    private var downX = 0f; private var downY = 0f; private var downRawX = 0f; private var downRawY = 0f
    private var axis = 0          // 0 undecided, 1 horizontal, 2 vertical
    private var off = 0f          // drawn card offset along the axis
    private var mode = 0          // widget side when the gesture started
    private var cur: Bitmap? = null
    private var other: Bitmap? = null
    private var vt: VelocityTracker? = null
    private var anim: ValueAnimator? = null
    private var holdBg = false    // after a skip: keep the empty card up until the new track has drawn
    private var flash: Array<String>? = null
    private var flashA = 0f
    private var fade = 1f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    /** held long enough: buzz now, hand the long-press to the launcher on release (a replay while the finger is down gets cancelled) */
    private var longHeld = false
    private val longPress = Runnable {
        if (axis == 0) { longHeld = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) }
    }

    private fun media() = MediaListenerService.instance

    fun reset() {
        removeCallbacks(longPress); anim?.cancel(); anim = null
        axis = 0; off = 0f; cur = null; longHeld = false; other = null; holdBg = false; flash = null; flashA = 0f; fade = 1f
        vt?.recycle(); vt = null; busy = false; invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> { if (!busy) svc.outsideTouch(); return false }
            MotionEvent.ACTION_DOWN -> {
                if (anim != null) return true  // let a running animation finish
                reset(); busy = true
                downX = e.x; downY = e.y; downRawX = e.rawX; downRawY = e.rawY
                mode = Prefs.artMode(context)
                vt = VelocityTracker.obtain().also { it.addMovement(e) }
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX; val dy = e.y - downY
                if (axis == 0 && !longHeld && hypot(dx, dy) > slop) {
                    removeCallbacks(longPress)
                    axis = if (abs(dx) > abs(dy)) 1 else 2
                    val s = media()
                    cur = s?.artFrame(mode, width, height, d)?.let { it.copy(it.config ?: Bitmap.Config.ARGB_8888, false) }
                    if (axis == 2) other = s?.artFrame(1 - mode, width, height, d)
                }
                if (axis == 1) off = rubber(dx, width.toFloat())
                if (axis == 2) {
                    // only the direction that leads somewhere moves freely: up from the cover, down from lyrics
                    val toward = if (mode == 0) dy < 0 else dy > 0
                    off = if (toward) dy.coerceIn(-height.toFloat(), height.toFloat()) else rubber(dy, height.toFloat()) * .35f
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f; val vy = vt?.yVelocity ?: 0f
                when (axis) {
                    0 -> if (longHeld) svc.passLongPress(downRawX, downRawY) else tap()
                    1 -> {
                        val go = abs(off) > width * .28f || (abs(vx) > flingV && vx * off > 0 && abs(off) > slop)
                        if (go) skip(off > 0) else settle()
                    }
                    2 -> {
                        val toward = if (mode == 0) off < 0 else off > 0
                        val go = toward && (abs(off) > height * .3f || (abs(vy) > flingV && vy * off > 0))
                        if (go) flip() else settle()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); if (axis == 0) reset() else settle() }
        }
        return true
    }

    /** 1:1 up to 45% of the size, then increasingly stiff */
    private fun rubber(v: Float, size: Float): Float {
        val lim = size * .45f
        val a = abs(v)
        val r = if (a <= lim) a else lim + (a - lim) * .35f
        return if (v < 0) -r else r
    }

    private fun animate(from: Float, to: Float, ms: Long, onEnd: () -> Unit, step: (Float) -> Unit) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(from, to).apply {
            duration = ms; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { step(it.animatedValue as Float); invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { anim = null; onEnd() }
            })
            start()
        }
    }

    private fun settle() = animate(off, 0f, 220, { reset() }) { off = it }

    private fun tap() {
        val s = media() ?: run { reset(); return }
        flash = if (s.isPlaying()) Renderers.G_PAUSE else Renderers.G_PLAY
        s.transport(LyricsWidgetProvider.ACT_PLAY)
        animate(1f, 0f, 650, { reset() }) { flashA = it }
    }

    private fun skip(prev: Boolean) {
        val s = media()
        val ver = s?.trackVer ?: 0
        val target = if (prev) width.toFloat() * 1.05f else -width.toFloat() * 1.05f
        animate(off, target, 170, {
            holdBg = true; invalidate()
            s?.transport(if (prev) LyricsWidgetProvider.ACT_PREV else LyricsWidgetProvider.ACT_NEXT)
            val t0 = SystemClock.uptimeMillis()
            // wait for the new track (or 900 ms), give the launcher a moment to draw it, then fade out
            val wait = object : Runnable {
                override fun run() {
                    val changed = (media()?.trackVer ?: ver) != ver
                    if (changed || SystemClock.uptimeMillis() - t0 > 900) postDelayed({ fadeOut() }, if (changed) 160 else 0)
                    else postDelayed(this, 40)
                }
            }
            post(wait)
        }) { off = it }
    }

    private fun fadeOut() = animate(1f, 0f, 200, { reset() }) { fade = it }

    private fun flip() {
        val target = if (mode == 0) -height.toFloat() else height.toFloat()
        animate(off, target, 200, {
            media()?.setArtMode(1 - mode) ?: Prefs.setArtMode(context, 1 - mode)
            // the overlay now shows exactly what the widget will show; drop it once the launcher has it
            postDelayed({ fadeOut() }, 180)
        }) { off = it }
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = Prefs.artRadius(context) * d
        flash?.let { g ->
            if (flashA <= 0f) return@let
            p.color = Color.argb((flashA * 140).toInt(), 0, 0, 0)
            cv.drawRoundRect(0f, 0f, w, h, r, r, p)
            p.color = Color.argb((flashA * 255).toInt(), 255, 255, 255)
            Renderers.glyph(cv, g, w / 2 + (if (g === Renderers.G_PLAY) 2 * d else 0f), h / 2, d * 2.2f, p)
        }
        if (axis == 0) return
        val sc = cv.saveLayerAlpha(0f, 0f, w, h, (fade * 255).toInt())
        clip.rewind(); clip.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        cv.clipPath(clip)
        p.color = 0xFF0E0E0E.toInt(); cv.drawRect(0f, 0f, w, h, p)
        if (axis == 1) {
            // revealed side: ⏮ on the left when pulling right, ⏭ on the right when pulling left
            val prog = (abs(off) / (w * .28f)).coerceIn(0f, 1f)
            p.color = if (prog >= 1f) Renderers.RED else Color.argb((80 + prog * 175).toInt(), 242, 242, 242)
            val gx = if (off > 0) (off / 2).coerceAtMost(w / 2) else w + (off / 2).coerceAtLeast(-w / 2)
            if (abs(off) > 8 * d) Renderers.glyph(cv, if (off > 0) Renderers.G_PREV else Renderers.G_NEXT, gx, h / 2, d * 1.6f, p)
            if (!holdBg) cur?.let { cv.drawBitmap(it, off, 0f, p.apply { alpha = 255 }) }
        } else {
            p.alpha = 255
            cur?.let { cv.drawBitmap(it, 0f, off, p) }
            val toward = if (mode == 0) off < 0 else off > 0
            if (toward) other?.let { cv.drawBitmap(it, 0f, if (off < 0) off + h else off - h, p) }
        }
        cv.restoreToCount(sc)
    }
}
