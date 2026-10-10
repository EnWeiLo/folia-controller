package tw.folia.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** A small looping animation of one gesture (a fingertip tapping, swiping or holding) for the tutorial. */
class GestureGuideView(context: Context, private val kind: Kind, private val accent: Int) : View(context) {

    enum class Kind { TAP, DOUBLE_TAP, SWIPE_LEFT, SWIPE_RIGHT, SWIPE_UP, LONG_PRESS }

    private val density = resources.displayMetrics.density
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
    private val finger = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = accent
    }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f * density
    }
    private val rect = RectF()
    private var t = 0f
    private val loopMs = if (kind == Kind.LONG_PRESS) 2000L else 1600L

    // Driven by the clock rather than an Animator, so it still plays when the phone's
    // "animation speed" / "remove animations" setting is off.

    private fun ease(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3 - 2 * c)
    }

    /** 0 → 1 over [from, to] of the loop. */
    private fun phase(from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        t = (SystemClock.uptimeMillis() % loopMs) / loopMs.toFloat()
        if (isAttachedToWindow) postInvalidateOnAnimation()
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, 16f * density, 16f * density, box)
        val cx = w / 2
        val cy = h / 2
        val r = min(w, h) * 0.1f

        when (kind) {
            Kind.TAP -> {
                tapAt(canvas, cx, cy, r, 0.1f)
            }
            Kind.DOUBLE_TAP -> {
                tapAt(canvas, cx, cy, r, 0.08f)
                tapAt(canvas, cx, cy, r, 0.32f)
            }
            Kind.SWIPE_LEFT, Kind.SWIPE_RIGHT, Kind.SWIPE_UP -> {
                val p = ease(phase(0.12f, 0.6f))
                val visible = t in 0.05f..0.75f
                val fade = if (t > 0.6f) 1f - phase(0.6f, 0.75f) else min(1f, phase(0.05f, 0.12f))
                val (sx, sy, ex, ey) = when (kind) {
                    Kind.SWIPE_LEFT -> floatArrayOf(w * 0.75f, cy, w * 0.25f, cy)
                    Kind.SWIPE_RIGHT -> floatArrayOf(w * 0.25f, cy, w * 0.75f, cy)
                    else -> floatArrayOf(cx, h * 0.78f, cx, h * 0.22f)
                }.let { listOf(it[0], it[1], it[2], it[3]) }
                val x = sx + (ex - sx) * p
                val y = sy + (ey - sy) * p
                if (visible) {
                    trail.color = withAlpha(accent, (0.55f * fade))
                    canvas.drawLine(sx, sy, x, y, trail)
                    finger.alpha = (242 * fade).toInt()
                    canvas.drawCircle(x, y, r, finger)
                    finger.alpha = 242
                }
            }
            Kind.LONG_PRESS -> {
                val shown = t in 0.05f..0.85f
                if (shown) {
                    val fade = if (t > 0.75f) 1f - phase(0.75f, 0.85f) else min(1f, phase(0.05f, 0.12f))
                    val hold = phase(0.12f, 0.62f)
                    ring.alpha = (255 * fade).toInt()
                    rect.set(cx - r * 2.2f, cy - r * 2.2f, cx + r * 2.2f, cy + r * 2.2f)
                    canvas.drawArc(rect, -90f, 360f * hold, false, ring)
                    finger.alpha = (242 * fade).toInt()
                    canvas.drawCircle(cx, cy, r * (1f - 0.12f * min(1f, hold * 4)), finger)
                    finger.alpha = 242
                }
            }
        }
    }

    /** A fingertip that touches down at [start] and sends out one ripple. */
    private fun tapAt(canvas: Canvas, x: Float, y: Float, r: Float, start: Float) {
        val down = phase(start, start + 0.06f)
        val up = phase(start + 0.1f, start + 0.18f)
        val ripple = phase(start + 0.04f, start + 0.4f)
        if (ripple in 0.001f..0.999f) {
            ring.alpha = (255 * (1f - ripple)).toInt()
            canvas.drawCircle(x, y, r * (1.2f + 1.6f * ripple), ring)
        }
        val alpha = max(0f, down - up)
        if (alpha > 0f) {
            finger.alpha = (242 * alpha).toInt()
            canvas.drawCircle(x, y, r * (1.15f - 0.15f * down), finger)
            finger.alpha = 242
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (color and 0xFFFFFF)
}
