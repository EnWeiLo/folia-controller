package tw.folia.controller

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 懸浮歌詞: two lines (the lyric and its translation in small type) floating over every app, like the
 * Windows desktop lyrics. Drag to move, pinch to resize, lock to let touches pass through. Hidden
 * while 流詞 itself is on screen. Runs as a foreground service with its own notification.
 */
class FloatingLyricsService : Service() {
    companion object {
        private const val CHANNEL = "floating_lyrics"
        private const val NOTIFICATION_ID = 7
        const val ACTION_LOCK = "tw.folia.controller.FLOAT_LOCK"
        const val ACTION_STOP = "tw.folia.controller.FLOAT_STOP"
        const val MIN_SP = 14f
        const val MAX_SP = 40f

        @Volatile var running = false
            private set

        /** True while MainActivity is in front: the window hides itself. */
        @Volatile var appInFront = false

        fun canDraw(context: Context) = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            val intent = Intent(context, FloatingLyricsService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingLyricsService::class.java))
        }
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager
    private var view: LyricsView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            view?.update()
            handler.postDelayed(this, 120)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = getSharedPreferences("folia_controller", MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        MediaHub.init(this)
        MediaHub.convertToTraditional = prefs.getBoolean("traditional", true)
        MediaHub.lyricOffsetMs = prefs.getLong("offset", 0L)
        if (MediaHub.hasNotificationAccess(this)) MediaHub.start()
        startInForeground()
        if (canDraw(this)) addWindow()
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOCK -> setLocked(!prefs.getBoolean("float_locked", false))
            ACTION_STOP -> {
                prefs.edit().putBoolean("floating_lyrics", false).apply()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        super.onDestroy()
    }

    // ---------- notification ----------

    private fun startInForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "懸浮歌詞", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "懸浮歌詞開著時的常駐通知"
                    setShowBadge(false)
                },
            )
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val locked = prefs.getBoolean("float_locked", false)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val lock = PendingIntent.getService(this, 1, Intent(this, FloatingLyricsService::class.java).setAction(ACTION_LOCK), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, FloatingLyricsService::class.java).setAction(ACTION_STOP), flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_fc_lyrics)
            .setContentTitle("懸浮歌詞")
            .setContentText(if (locked) "已鎖定：手指會穿過歌詞" else "拖動移動位置，兩指縮放字體大小")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, if (locked) "解除鎖定" else "鎖定", lock).build())
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "關閉", stop).build())
            .build()
    }

    private fun refreshNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification())
    }

    // ---------- the window ----------

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    @SuppressLint("ClickableViewAccessibility")
    private fun addWindow() {
        val metrics = resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            (min(metrics.widthPixels, metrics.heightPixels) * 0.94f).toInt().coerceAtLeast(dp(260f).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("float_x", ((metrics.widthPixels - width) / 2))
            y = prefs.getInt("float_y", (metrics.heightPixels * 0.72f).toInt())
        }
        params = lp
        val v = LyricsView(this)
        view = v
        applyLock(lp, prefs.getBoolean("float_locked", false))
        windowManager.addView(v, lp)
    }

    private fun applyLock(lp: WindowManager.LayoutParams, locked: Boolean) {
        lp.flags = if (locked) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        view?.locked = locked
    }

    fun setLocked(locked: Boolean) {
        prefs.edit().putBoolean("float_locked", locked).apply()
        val lp = params ?: return
        applyLock(lp, locked)
        view?.let { runCatching { windowManager.updateViewLayout(it, lp) }; it.invalidate() }
        refreshNotification()
    }

    private inner class LyricsView(context: Context) : View(context) {
        var locked = false
        private var main = ""
        private var sub = ""
        private var shownAlpha = 0f
        private var sizeSp = prefs.getFloat("float_size", 22f).coerceIn(MIN_SP, MAX_SP)
        private val mainPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
            textAlign = Paint.Align.CENTER
        }
        private val outline = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC000000.toInt()
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            textAlign = Paint.Align.CENTER
        }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
        private val frameEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
        }
        private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                sizeSp = (sizeSp * detector.scaleFactor).coerceIn(MIN_SP, MAX_SP)
                requestLayout()
                invalidate()
                return true
            }
        })
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        private fun mainPx() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)

        fun update() {
            val pair = if (appInFront || MediaHub.track == null) null else MediaHub.currentLyricPair()
            val want = if (pair == null || (pair.first.isBlank() && pair.second.isBlank())) 0f else 1f
            var changed = false
            if (pair != null && (pair.first != main || pair.second != sub)) {
                main = pair.first
                sub = pair.second
                changed = true
            }
            val step = if (want > shownAlpha) 0.25f else 0.15f
            val next = if (want > shownAlpha) min(want, shownAlpha + step) else max(want, shownAlpha - step)
            if (next != shownAlpha) { shownAlpha = next; changed = true }
            if (changed) invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val px = mainPx()
            val h = (px * 1.35f + px * 0.62f * 1.5f + dp(16f)).toInt()
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
        }

        override fun onDraw(canvas: Canvas) {
            val unlockedFrame = !locked
            if (unlockedFrame) {
                // a faint frame so it can be found and dragged while unlocked
                val r = RectF(dp(1f), dp(1f), width - dp(1f), height - dp(1f))
                frame.alpha = (0x40 * max(shownAlpha, 0.6f)).toInt()
                canvas.drawRoundRect(r, dp(14f), dp(14f), frame)
                canvas.drawRoundRect(r, dp(14f), dp(14f), frameEdge)
            }
            if (shownAlpha <= 0.01f) return
            val px = mainPx()
            val maxW = width - dp(24f)
            mainPaint.textSize = px
            subPaint.textSize = px * 0.62f
            mainPaint.setShadowLayer(dp(3f), 0f, dp(1f), 0x99000000.toInt())
            subPaint.setShadowLayer(dp(2.5f), 0f, dp(1f), 0x99000000.toInt())
            val a = (255 * shownAlpha).toInt()
            val line1 = TextUtils.ellipsize(main, mainPaint, maxW, TextUtils.TruncateAt.END).toString()
            val line2 = TextUtils.ellipsize(sub, subPaint, maxW, TextUtils.TruncateAt.END).toString()
            val cx = width / 2f
            val y1 = dp(8f) + px
            val y2 = y1 + px * 0.62f * 1.45f
            outline.textSize = px
            outline.strokeWidth = px * 0.14f
            outline.alpha = (0xCC * shownAlpha).toInt()
            canvas.drawText(line1, cx, y1, outline)
            mainPaint.alpha = a
            canvas.drawText(line1, cx, y1, mainPaint)
            if (line2.isNotEmpty()) {
                outline.textSize = px * 0.62f
                outline.strokeWidth = px * 0.62f * 0.14f
                canvas.drawText(line2, cx, y2, outline)
                subPaint.alpha = (0xE6 * shownAlpha).toInt()
                canvas.drawText(line2, cx, y2, subPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val lp = params ?: return false
            scaler.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaler.isInProgress) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (dragging || abs(dx) > dp(4f) || abs(dy) > dp(4f)) {
                            dragging = true
                            lp.x = (startX + dx).toInt()
                            lp.y = (startY + dy).toInt()
                            runCatching { windowManager.updateViewLayout(this, lp) }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit()
                        .putInt("float_x", lp.x)
                        .putInt("float_y", lp.y)
                        .putFloat("float_size", sizeSp)
                        .apply()
                }
            }
            return true
        }
    }
}
