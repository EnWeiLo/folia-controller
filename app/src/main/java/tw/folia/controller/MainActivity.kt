package tw.folia.controller

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private data class Mode(val id: String, val label: String)

    /** [renderScale]: share of physical pixels Folia renders at; [staticMode]: Folia's 靜態模式 (no geometric background). */
    private data class Quality(val id: String, val label: String, val detail: String, val renderScale: Float, val staticMode: Boolean)

    companion object {
        // Folia's builtin lyric animations (src/types/visualizerModes.ts), with their Chinese names.
        private val MODES = listOf(
            Mode("classic", "流光"), Mode("cadenza", "心象"), Mode("partita", "雲階"),
            Mode("fume", "浮名"), Mode("monet", "莫奈"), Mode("cappella", "群唱"),
            Mode("tilt", "傾訴"), Mode("claddagh", "回環"), Mode("diorama", "鏡台"),
            Mode("pendolo", "時計"), Mode("sonnet", "商籟"), Mode("tempera", "凝彩"),
            Mode("lumiere", "繪光"), Mode("still", "靜止"),
        )
        // Flowrics' own effects (shared/web/flowrics-fx*.js); ids are "fx:<effect>".
        private val FX_MODES = listOf(
            Mode("fx:dive", "潛入"), Mode("fx:minimal", "極簡"), Mode("fx:karaoke", "卡拉OK"),
            Mode("fx:cinema", "電影字幕"), Mode("fx:ink", "墨染"), Mode("fx:ripple", "液面"),
            Mode("fx:stardust", "星塵"), Mode("fx:neon", "霓虹"), Mode("fx:vinyl", "黑膠"),
            Mode("fx:comic", "漫畫對白"), Mode("fx:terminal", "終端"), Mode("fx:danmaku", "彈幕"),
            Mode("fx:led", "LED 看板"),
        )
        private val WILD_MODES = listOf(
            Mode("fx:boom", "爆破"), Mode("fx:meteor", "隕石"), Mode("fx:glitch", "故障"),
            Mode("fx:vortex", "漩渦"), Mode("fx:fireworks", "煙火"), Mode("fx:jelly", "果凍"),
            Mode("fx:warp", "曲速"), Mode("fx:shatter", "碎玻璃"),
        )
        private const val DEFAULT_MODE = "fx:dive"
        private fun modeLabel(id: String?): String =
            (FX_MODES + WILD_MODES + MODES).firstOrNull { it.id == id }?.label ?: (id ?: "")
        private val QUALITIES = listOf(
            Quality("high", "高畫質", "原生解析度，最清晰也最吃效能", 1f, false),
            Quality("balanced", "均衡", "約 75% 解析度，文字依然清晰", 0.75f, false),
            Quality("smooth", "流暢", "約 60% 解析度，並關閉幾何背景", 0.6f, true),
        )
        private const val DEFAULT_QUALITY = "balanced"
        // Folia's subtitle line under the lyrics (subtitleContentMode).
        private val SUBTITLES = listOf(
            "translation" to "中文翻譯", "romanization" to "羅馬拼音", "both" to "翻譯＋羅馬拼音", "none" to "不顯示",
        )
        private val OFFSETS = longArrayOf(-1000, -500, -250, 0, 250, 500, 750, 1000, 1500)
        private const val REQUEST_LYRICS_FOLDER = 41
        private const val REQUEST_AUDIO = 42
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var assetServer: FoliaAssetServer
    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var gestureLayer: View
    private lateinit var card: LinearLayout
    private lateinit var coverView: ImageView
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var statusText: TextView
    private lateinit var seekRow: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var playButton: ImageButton
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var lyricsButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var feedback: LinearLayout
    private lateinit var feedbackIcon: ImageView
    private lateinit var feedbackText: TextView
    private lateinit var permissionPanel: LinearLayout
    private var tutorial: View? = null
    private var cardWide: Boolean? = null
    private var appliedPalette: CoverPalette? = null
    private var appliedCover: Bitmap? = null

    private val handler = Handler(Looper.getMainLooper())
    private val audio = AudioReactive { b64, playing ->
        webView.evaluateJavascript("window.__fcAudioFrame&&__fcAudioFrame('$b64',${if (playing) 1 else 0})", null)
    }
    /** The info card stays until a tap hides it (no auto-hide). */
    private var controlsVisible = true
    private var userSeeking = false
    private var resumed = false

    private val progressTicker = object : Runnable {
        override fun run() {
            if (controlsVisible) updateProgressUi()
            handler.postDelayed(this, 500)
        }
    }

    // Tells the FPS readout when the screen actually switches refresh rate.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val hz = currentDisplay()?.refreshRate ?: return
            webView.evaluateJavascript("window.__foliaSetDisplayHz&&window.__foliaSetDisplayHz($hz)", null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("folia_controller", MODE_PRIVATE)
        assetServer = FoliaAssetServer(this) { currentPageSettings() }
        MediaHub.init(this)
        applyHubPrefs()

        window.decorView.setBackgroundColor(Color.BLACK)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContentView(buildLayout())
        applyRefreshRate()
        applyKeepScreenOn()
        enterImmersive()

        MediaHub.webSink = { json ->
            webView.evaluateJavascript("window.__foliaPush&&window.__foliaPush(${JSONObject.quote(json)})", null)
        }
        MediaHub.uiListener = { refreshUi() }
        if (intent.getBooleanExtra("demo", false)) {
            MediaHub.startDemo(demoCover())
            // CI screenshots of the 動畫與外觀 panel.
            if (intent.getBooleanExtra("tuning", false)) handler.postDelayed({ openTuning() }, 18000)
        }
        loadFolia()
        refreshUi()
    }

    /** A made-up cover for the interface preview. */
    private fun demoCover(): Bitmap {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
            0xFF2B3A8C.toInt(), 0xFF7A4FC4.toInt(), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        paint.color = 0x33FFFFFF
        canvas.drawCircle(size * 0.7f, size * 0.35f, size * 0.22f, paint)
        paint.color = 0x22FFFFFF
        canvas.drawCircle(size * 0.3f, size * 0.72f, size * 0.3f, paint)
        return bitmap
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        webView.onResume()
        applyRefreshRate()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, handler)
        updatePermissionState()
        handler.post(progressTicker)
        startAudioIfWanted()
    }

    override fun onPause() {
        resumed = false
        audio.stop()
        handler.removeCallbacks(progressTicker)
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        MediaHub.webSink = null
        MediaHub.uiListener = null
        if (isFinishing) MediaHub.stop()
        webView.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    // ---------- layout ----------

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun dpf(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "ClickableViewAccessibility")
    private fun buildLayout(): View {
        val root = FrameLayout(this)
        rootLayout = root
        root.setBackgroundColor(Color.BLACK)

        WebView.setWebContentsDebuggingEnabled(true)
        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                textZoom = 100
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            addJavascriptInterface(JsBridge(), "FoliaNative")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetServer.intercept(request)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host != FoliaAssetServer.HOST

                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    // The renderer crashed or was killed for memory: rebuild everything.
                    recreate()
                    return true
                }
            }
        }
        root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))

        // Transparent layer over the lyrics that turns touches into gestures (Folia's page needs no touch).
        val gestures = GestureDetector(this, GestureListener())
        val tapLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { _, event -> gestures.onTouchEvent(event); true }
        }
        gestureLayer = tapLayer
        root.addView(tapLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        feedback = buildFeedback()
        root.addView(feedback, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        card = buildCard()
        root.addView(card)
        arrangeCard()
        card.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) updateFoliaInset()
        }

        permissionPanel = buildPermissionPanel().apply { elevation = dpf(24f) }
        root.addView(permissionPanel, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    private fun label(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun buildCard(): LinearLayout {
        coverView = ImageView(this).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpf(12f))
                }
            }
            clipToOutline = true
            contentDescription = "開啟播放器"
            setOnClickListener { openSourceApp() }
        }
        titleText = label(17f, Color.WHITE, bold = true)
        subtitleText = label(13f, 0xA6FFFFFF.toInt()).apply { setPadding(0, dp(4), 0, 0) }
        statusText = label(11.5f, 0x73FFFFFF).apply {
            setPadding(0, dp(5), 0, 0)
            setOnClickListener { showLyricPicker() }
        }

        positionText = timeLabel()
        durationText = timeLabel()
        seekBar = SeekBar(this).apply {
            max = 1
            splitTrack = false
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) positionText.text = formatTime(progress.toLong())
                }
                override fun onStartTrackingTouch(bar: SeekBar) {
                    userSeeking = true
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    userSeeking = false
                    MediaHub.seekTo(bar.progress.toLong())
                }
            })
        }
        seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(positionText, LinearLayout.LayoutParams(dp(38), WRAP))
            addView(seekBar, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(durationText, LinearLayout.LayoutParams(dp(38), WRAP))
        }

        prevButton = iconButton(R.drawable.ic_fc_prev, 46, 26, "上一首") { MediaHub.skipPrevious() }
        nextButton = iconButton(R.drawable.ic_fc_next, 46, 26, "下一首") { MediaHub.skipNext() }
        playButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_fc_play)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            contentDescription = "播放／暫停"
            setOnClickListener { MediaHub.togglePlayPause() }
        }
        lyricsButton = iconButton(R.drawable.ic_fc_lyrics, 40, 20, "換歌詞") { showLyricPicker() }.apply { alpha = 0.75f }
        settingsButton = iconButton(R.drawable.ic_fc_tune, 40, 20, "設定") { showSettings() }.apply { alpha = 0.75f }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // taps on the card don't reach the gesture layer
            elevation = dpf(8f)
        }
    }

    private fun detach(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    /** Phones get a stacked card; tablets and landscape get everything on one row. */
    private fun arrangeCard() {
        val widthDp = resources.configuration.screenWidthDp
        val wide = widthDp >= 600
        if (wide != cardWide) {
            cardWide = wide
            listOf(coverView, titleText, subtitleText, statusText, seekRow, prevButton, playButton, nextButton, lyricsButton, settingsButton)
                .forEach(::detach)
            card.removeAllViews()
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleText, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(subtitleText, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(statusText, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            val transport = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(prevButton)
                addView(playButton, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginStart = dp(6); marginEnd = dp(6) })
                addView(nextButton)
            }
            if (wide) {
                card.setPadding(dp(16), dp(16), dp(14), dp(14))
                val column = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(info, LinearLayout.LayoutParams(MATCH, WRAP))
                    addView(seekRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
                }
                val extras = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(lyricsButton)
                    addView(settingsButton)
                }
                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(coverView, LinearLayout.LayoutParams(dp(84), dp(84)))
                    addView(column, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(16); marginEnd = dp(8) })
                    addView(transport)
                    addView(extras, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
                }, LinearLayout.LayoutParams(MATCH, WRAP))
            } else {
                card.setPadding(dp(16), dp(16), dp(16), dp(10))
                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(coverView, LinearLayout.LayoutParams(dp(64), dp(64)))
                    addView(info, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
                }, LinearLayout.LayoutParams(MATCH, WRAP))
                card.addView(seekRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(lyricsButton)
                    addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(transport)
                    addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(settingsButton)
                }, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
        val screen = resources.displayMetrics.widthPixels
        card.layoutParams = FrameLayout.LayoutParams(
            (screen - dp(24)).coerceAtMost(dp(if (wide) 860 else 520)), WRAP,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = dp(if (wide) 24 else 16) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        arrangeCard()
        tutorial?.let {
            hideGestureTutorial(remember = false)
            showGestureTutorial()
        }
    }

    private fun timeLabel() = TextView(this).apply {
        setTextColor(0x99FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        gravity = Gravity.CENTER
        fontFeatureSettings = "tnum"
        text = formatTime(0)
    }

    private fun iconButton(icon: Int, sizeDp: Int, iconDp: Int, description: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
        foreground = rippleOval()
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = dp((sizeDp - iconDp) / 2)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        contentDescription = description
        setOnClickListener { onClick() }
    }

    private fun rippleOval() = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x33FFFFFF),
        null,
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) },
    )

    /** Colours and cover from the current song. */
    private fun applyCardLook() {
        val palette = MediaHub.palette
        if (palette != appliedPalette) {
            appliedPalette = palette
            card.background = GradientDrawable().apply {
                cornerRadius = dpf(22f)
                setColor((palette.card and 0xFFFFFF) or (0xE6 shl 24))
                setStroke(dp(1), 0x1FFFFFFF)
            }
            playButton.setColorFilter(palette.card)
            val radius = dpf(2f)
            val track = LayerDrawable(arrayOf(
                GradientDrawable().apply { cornerRadius = radius; setColor(0x33FFFFFF) },
                ClipDrawable(GradientDrawable().apply { cornerRadius = radius; setColor(palette.highlight) }, Gravity.START, ClipDrawable.HORIZONTAL),
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
                for (i in 0..1) {
                    setLayerHeight(i, dp(3))
                    setLayerGravity(i, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
                }
            }
            val progress = seekBar.progress
            seekBar.progressDrawable = track
            seekBar.thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setSize(dp(12), dp(12))
            }
            seekBar.progress = progress
        }
        val cover = MediaHub.coverBitmap
        if (cover !== appliedCover || coverView.drawable == null) {
            appliedCover = cover
            if (cover != null) {
                coverView.setImageBitmap(cover)
                coverView.scaleType = ImageView.ScaleType.CENTER_CROP
                coverView.setPadding(0, 0, 0, 0)
                coverView.background = null
            } else {
                coverView.setImageResource(R.drawable.ic_fc_music)
                coverView.scaleType = ImageView.ScaleType.FIT_CENTER
                coverView.setColorFilter(0x80FFFFFF.toInt())
                val pad = dp(18)
                coverView.setPadding(pad, pad, pad, pad)
                coverView.background = GradientDrawable().apply { setColor(0x1FFFFFFF) }
            }
            if (cover != null) coverView.clearColorFilter()
        }
    }

    // ---------- gestures ----------

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            setControlsVisible(!controlsVisible)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val willPlay = !MediaHub.isPlaying()
            MediaHub.togglePlayPause()
            showFeedback(if (willPlay) R.drawable.ic_fc_play else R.drawable.ic_fc_pause, if (willPlay) "播放" else "暫停", 0)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            gestureLayer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showLyricPicker()
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            val minDistance = dpf(64f)
            val minSpeed = dpf(250f)
            when {
                abs(dx) > minDistance && abs(dx) > abs(dy) * 1.3f && abs(velocityX) > minSpeed -> {
                    if (dx < 0) {
                        MediaHub.skipNext()
                        showFeedback(R.drawable.ic_fc_next, "下一首", -1)
                    } else {
                        MediaHub.skipPrevious()
                        showFeedback(R.drawable.ic_fc_prev, "上一首", 1)
                    }
                }
                dy < -minDistance && abs(dy) > abs(dx) * 1.3f && abs(velocityY) > minSpeed -> showSettings()
                else -> return false
            }
            return true
        }
    }

    private fun buildFeedback(): LinearLayout {
        feedbackIcon = ImageView(this).apply {
            setColorFilter(Color.WHITE)
            val pad = dp(24)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x8C000000.toInt()) }
        }
        feedbackText = label(14f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setShadowLayer(dpf(6f), 0f, 0f, 0xCC000000.toInt())
            setPadding(0, dp(10), 0, 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(feedbackIcon, LinearLayout.LayoutParams(dp(88), dp(88)))
            addView(feedbackText, LinearLayout.LayoutParams(WRAP, WRAP))
        }
    }

    /** A short-lived icon in the middle of the screen confirming what a gesture did. [direction]: −1 left, 1 right. */
    private fun showFeedback(icon: Int, text: String, direction: Int) {
        feedbackIcon.setImageResource(icon)
        feedbackText.text = text
        feedback.animate().cancel()
        feedback.visibility = View.VISIBLE
        feedback.alpha = 0f
        feedback.scaleX = 0.85f
        feedback.scaleY = 0.85f
        feedback.translationX = -direction * dpf(28f)
        feedback.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).setStartDelay(0).setDuration(160)
            .withEndAction {
                feedback.animate().alpha(0f).setStartDelay(450).setDuration(260)
                    .withEndAction { feedback.visibility = View.GONE }.start()
            }.start()
    }

    // ---------- 跟著音樂律動 ----------

    private fun hasAudioPermission() =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun startAudioIfWanted() {
        if (!prefs.getBoolean("audio_reactive", false) || !hasAudioPermission() || audio.running) return
        val error = audio.start()
        if (error != null) {
            android.widget.Toast.makeText(this, "這台裝置不讓 App 讀取正在播放的聲音（$error）", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleAudio() {
        if (prefs.getBoolean("audio_reactive", false)) {
            prefs.edit().putBoolean("audio_reactive", false).apply()
            audio.stop()
            return
        }
        if (hasAudioPermission()) {
            prefs.edit().putBoolean("audio_reactive", true).apply()
            startAudioIfWanted()
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("跟著音樂律動")
            .setMessage("讓有律動效果的歌詞動畫跟著節奏動。\n\nAndroid 把「讀取正在播放的聲音頻譜」歸在「錄音」權限底下，所以需要你允許。App 只讀取頻譜的強弱，不會錄音、存檔或上傳。")
            .setPositiveButton("繼續") { _, _ -> requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO) }
            .setNegativeButton("取消", null)
            .setOnDismissListener { enterImmersive() }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_AUDIO) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("audio_reactive", true).apply()
            startAudioIfWanted()
        } else {
            android.widget.Toast.makeText(this, "沒有權限，動畫不會跟著音樂律動", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 動畫與外觀 panel (fc-tuning.js, inside the WebView) ----------

    private var tuningOpen = false

    /** The panel is web content, so the gesture layer and the card step aside while it's open. */
    private fun openTuning() {
        tuningOpen = true
        gestureLayer.visibility = View.GONE
        card.visibility = View.GONE
        val dpWidth = webView.width / resources.displayMetrics.density
        webView.evaluateJavascript(
            "window.FcTuning?(FcTuning.open({dpWidth:${String.format(Locale.US, "%.1f", dpWidth)}," +
                "onClose:function(){FoliaNative.tuningClosed()}}),true):false",
        ) { result ->
            android.util.Log.i("FoliaController", "tuning panel open: $result")
            // The page is still loading (or failed): give the screen back and say so.
            if (result != "true") {
                tuningClosed()
                android.widget.Toast.makeText(this, "畫面還在載入，請稍後再開一次", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun tuningClosed() {
        if (!tuningOpen) return
        tuningOpen = false
        gestureLayer.visibility = View.VISIBLE
        card.visibility = if (controlsVisible) View.VISIBLE else View.INVISIBLE
        enterImmersive()
    }

    // ---------- gesture tutorial ----------

    private fun showGestureTutorial() {
        if (tutorial != null) return
        val accent = MediaHub.palette.highlight
        val wide = resources.configuration.screenWidthDp >= 600
        val items = listOf(
            Triple(GestureGuideView.Kind.TAP, "點一下", "顯示或收起資訊卡"),
            Triple(GestureGuideView.Kind.DOUBLE_TAP, "點兩下", "播放／暫停"),
            Triple(GestureGuideView.Kind.SWIPE_LEFT, "往左滑", "下一首"),
            Triple(GestureGuideView.Kind.SWIPE_RIGHT, "往右滑", "上一首"),
            Triple(GestureGuideView.Kind.SWIPE_UP, "往上滑", "打開設定"),
            Triple(GestureGuideView.Kind.LONG_PRESS, "長按", "換歌詞"),
        )
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(36), dp(24), dp(36))
        }
        column.addView(label(24f, Color.WHITE, bold = true).apply {
            text = "手勢操作"
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(TextView(this).apply {
            text = "在歌詞畫面的任何地方都能用，不用找按鈕。"
            setTextColor(0xA6FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        val perRow = if (wide) 2 else 1
        items.chunked(perRow).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { (kind, name, action) ->
                line.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(7), dp(12), dp(7))
                    addView(GestureGuideView(context, kind, accent), LinearLayout.LayoutParams(dp(84), dp(68)))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), 0, 0, 0)
                        addView(label(16f, Color.WHITE, bold = true).apply { text = name })
                        addView(label(14f, 0xB3FFFFFF.toInt()).apply { text = action; setPadding(0, dp(4), 0, 0) })
                    })
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            column.addView(line, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        column.addView(TextView(this).apply {
            text = "從畫面中間開始滑，比較不會碰到系統的返回或回主畫面手勢。資訊卡上的按鈕一樣可以按。\n之後可以在「設定 → 手勢說明」再看一次。"
            setTextColor(0x80FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(20), 0, dp(24))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(TextView(this).apply {
            text = "開始使用"
            setTextColor(0xFF111114.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(40), dp(13), dp(40), dp(13))
            background = GradientDrawable().apply { cornerRadius = dpf(999f); setColor(Color.WHITE) }
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22000000), null,
                GradientDrawable().apply { cornerRadius = dpf(999f); setColor(Color.WHITE) },
            )
            setOnClickListener { hideGestureTutorial(remember = true) }
        })

        val scroll = ScrollView(this).apply {
            elevation = dpf(24f) // above the info card, which has its own elevation
            isFillViewport = true
            setBackgroundColor(0xF00B0B0F.toInt())
            isClickable = true
            alpha = 0f
            addView(FrameLayout(context).apply {
                addView(column, FrameLayout.LayoutParams(
                    (resources.displayMetrics.widthPixels).coerceAtMost(dp(if (wide) 760 else 480)), WRAP, Gravity.CENTER,
                ))
            }, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        tutorial = scroll
        rootLayout.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        scroll.animate().alpha(1f).setDuration(200).start()
    }

    private fun hideGestureTutorial(remember: Boolean) {
        val view = tutorial ?: return
        tutorial = null
        if (remember) prefs.edit().putBoolean("gesture_tutorial_shown", true).apply()
        view.animate().alpha(0f).setDuration(180).withEndAction { rootLayout.removeView(view) }.start()
    }

    @Deprecated("Activity's back handling without AndroidX.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            tutorial != null -> hideGestureTutorial(remember = true)
            tuningOpen -> webView.evaluateJavascript("window.FcTuning&&FcTuning.close()", null)
            else -> super.onBackPressed()
        }
    }

    private fun buildPermissionPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(0xF209090B.toInt())
        setPadding(dp(32), dp(32), dp(32), dp(32))
        isClickable = true
        visibility = View.GONE

        addView(TextView(context).apply {
            text = "需要「通知使用權」"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Folia 控制器要讀取 Spotify、YouTube Music 等 App 正在播放的歌曲和進度，" +
                "才能同步顯示歌詞並控制播放。它不會讀取或保存你的通知內容。"
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(20))
        })
        addView(Button(context).apply {
            text = "開啟通知使用權設定"
            setOnClickListener { openNotificationAccessSettings() }
        })
        addView(TextView(context).apply {
            text = "如果開關是灰色、或出現「受限制的設定」：\n先按下面的按鈕到 App 資訊 → 右上角 ⋮ → 「允許受限制的設定」，再回來開啟通知使用權。"
            setTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(8))
        })
        addView(Button(context).apply {
            text = "開啟 App 資訊"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })
    }

    // ---------- behaviour ----------

    private fun loadFolia() {
        // CI screenshots pick an effect for one run without touching the saved setting.
        val demoMode = if (MediaHub.demo) intent.getStringExtra("mode") else null
        val mode = demoMode ?: prefs.getString("mode", DEFAULT_MODE) ?: DEFAULT_MODE
        if (mode.startsWith("fx:")) {
            // Flowrics' own effect page; it reads the same feed and the 副歌詞 setting.
            val cfg = JSONObject().put("subtitleContentMode", subtitleMode())
            webView.loadUrl(
                FoliaAssetServer.ORIGIN + "/fx.html?fx=" + Uri.encode(mode.removePrefix("fx:")) +
                    "&host=android-bridge&cfg=" + Uri.encode(cfg.toString()),
            )
            return
        }
        val url = buildString {
            append(FoliaAssetServer.ORIGIN).append("/?obs=1&obsSource=now-playing&host=android-bridge")
            append("&visualizer=").append(Uri.encode(mode))
            if (prefs.getBoolean("daylight", false)) append("&daylight=1")
            val cfg = JSONObject().put("subtitleContentMode", subtitleMode())
            if (currentQuality().staticMode) cfg.put("staticMode", true)
            append("&cfg=").append(Uri.encode(cfg.toString()))
        }
        webView.loadUrl(url)
    }

    private fun updatePermissionState() {
        val demo = MediaHub.demo
        val granted = demo || MediaHub.hasNotificationAccess(this)
        permissionPanel.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted && !demo) {
            MediaHub.start()
            MediaHub.pushSnapshot()
        }
        refreshUi()
        val wantTutorial = if (demo) intent.getBooleanExtra("tutorial", false)
        else granted && !prefs.getBoolean("gesture_tutorial_shown", false)
        if (wantTutorial) showGestureTutorial()
    }

    private fun openNotificationAccessSettings() {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= 30) {
                add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    android.content.ComponentName(this@MainActivity, MediaListenerService::class.java).flattenToString(),
                ))
            }
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    private fun openSourceApp() {
        val intent = MediaHub.sourceAppLaunchIntent() ?: return
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun refreshUi() {
        val info = MediaHub.track
        titleText.text = info?.title?.takeIf { it.isNotBlank() } ?: "沒有正在播放的音樂"
        subtitleText.text = when {
            info == null -> "先在 Spotify 或 YouTube Music 播放一首歌"
            else -> listOf(info.artist, info.album).filter { it.isNotBlank() }.joinToString(" · ")
                .ifEmpty { MediaHub.sourceAppLabel() ?: "" }
        }
        statusText.text = MediaHub.lyricStatus
        statusText.visibility = if (MediaHub.lyricStatus.isBlank()) View.GONE else View.VISIBLE
        val playing = MediaHub.isPlaying()
        playButton.setImageResource(if (playing) R.drawable.ic_fc_pause else R.drawable.ic_fc_play)
        playButton.contentDescription = if (playing) "暫停" else "播放"
        applyCardLook()
        updateProgressUi()
    }

    private fun updateProgressUi() {
        val duration = MediaHub.track?.durationMs ?: 0L
        durationText.text = formatTime(duration)
        seekBar.isEnabled = duration > 0
        if (userSeeking) return
        val position = MediaHub.positionMs()
        seekBar.max = duration.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        seekBar.progress = position.coerceIn(0L, seekBar.max.toLong()).toInt()
        positionText.text = formatTime(position)
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    /** Lifts Folia's bottom subtitle line above the card while the card is open (see folia-bridge.js). */
    private fun updateFoliaInset() {
        val cssPx = if (controlsVisible && card.height > 0) {
            val margin = (card.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin ?: 0
            (card.height + margin + dp(12)) * currentQuality().renderScale
        } else 0f
        webView.evaluateJavascript(
            "window.__fcSetBottomInset&&window.__fcSetBottomInset(${String.format(Locale.US, "%.1f", cssPx)})", null,
        )
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        updateFoliaInset()
        card.animate().cancel()
        if (visible) {
            card.visibility = View.VISIBLE
            updateProgressUi()
            card.animate().alpha(1f).translationY(0f).setDuration(200).start()
        } else {
            card.animate().alpha(0f).translationY(dpf(24f)).setDuration(220).withEndAction {
                if (!controlsVisible) card.visibility = View.INVISIBLE
            }.start()
        }
    }

    // ---------- settings ----------

    private fun applyHubPrefs() {
        MediaHub.convertToTraditional = prefs.getBoolean("traditional", true)
        MediaHub.lyricOffsetMs = prefs.getLong("offset", 0L)
    }

    private fun applyKeepScreenOn() {
        if (prefs.getBoolean("keep_screen_on", true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------- local lyrics folder ----------

    private fun pickLyricsFolder() {
        val builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("本機歌詞資料夾")
            .setMessage(
                "選一個放 .lrc 歌詞檔的資料夾（子資料夾也會找）。檔名用「歌名」、「歌手 - 歌名」或「歌名 - 歌手」，" +
                    "播到那首歌時會優先使用這裡的歌詞。\n\n目前：${LocalLyrics.folderLabel ?: "未設定"}",
            )
            .setPositiveButton("選擇資料夾") { _, _ ->
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_LYRICS_FOLDER)
                } catch (_: ActivityNotFoundException) {
                    android.widget.Toast.makeText(this, "這台裝置沒有資料夾選擇器", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { enterImmersive() }
        if (LocalLyrics.folderUri != null) {
            builder.setNeutralButton("停用") { _, _ ->
                LocalLyrics.clearFolder()
                MediaHub.refreshLyrics()
            }
        }
        builder.show()
    }

    @Deprecated("Activity result API needs AndroidX; this app has no dependencies.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_LYRICS_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            LocalLyrics.setFolder(uri)
            MediaHub.refreshLyrics()
            android.widget.Toast.makeText(this, "已設定本機歌詞資料夾", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- lyric picker ----------

    private fun showLyricPicker() {
        val track = MediaHub.track
        if (track == null) {
            android.widget.Toast.makeText(this, "現在沒有正在播放的歌曲", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val waiting = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("換歌詞")
            .setMessage("正在搜尋各個歌詞來源…")
            .setNegativeButton("取消", null)
            .show()
        MediaHub.loadCandidates { candidates ->
            if (!waiting.isShowing) return@loadCandidates
            waiting.dismiss()
            showCandidateList(track, candidates)
        }
    }

    private fun showCandidateList(track: TrackInfo, candidates: List<LyricCandidate>) {
        val current = MediaHub.currentLyricCandidate
        val shown = candidates.take(30)
        val labels = shown.map { c ->
            val isCurrent = current != null && current.source == c.source && current.id == c.id
            val diff = if (track.durationMs > 0 && c.durationMs > 0) (c.durationMs - track.durationMs) / 1000.0 else null
            val diffText = when {
                diff == null -> ""
                kotlin.math.abs(diff) < 0.5 -> "（長度相同）"
                else -> String.format(Locale.US, "（%+.1f 秒）", diff)
            }
            val mark = when {
                isCurrent -> "  ✓ 使用中"
                c.accepted -> "  · 相符"
                else -> ""
            }
            "【${LyricRepository.sourceName(c.source)}】${c.title}\n${c.artist}  ${formatTime(c.durationMs)}$diffText$mark"
        }.toTypedArray()
        val builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("換歌詞：${track.title}")
            .setNegativeButton("這首不顯示歌詞") { _, _ -> MediaHub.chooseNone() }
            .setOnDismissListener { enterImmersive() }
        if (shown.isEmpty()) builder.setMessage("每個來源都找不到這首歌。")
        else builder.setItems(labels) { _, which ->
            val chosen = shown[which]
            MediaHub.chooseCandidate(chosen) { ok ->
                val text = if (ok) "已換成「${LyricRepository.sourceName(chosen.source)}」的歌詞，下次也會使用" else "這個來源抓不到歌詞，換一個試試"
                android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        if (MediaHub.lyricChoiceRemembered) builder.setNeutralButton("恢復自動選擇") { _, _ -> MediaHub.resetChoice() }
        builder.show()
    }

    // ---------- quality & refresh rate ----------

    private fun currentQuality(): Quality =
        QUALITIES.firstOrNull { it.id == prefs.getString("quality", DEFAULT_QUALITY) } ?: QUALITIES[1]

    private fun refreshPref(): String = prefs.getString("refresh", "auto") ?: "auto"

    /** Folia's own frame cap: none when we want the screen's top speed, otherwise the chosen rate. */
    private fun frameCap(): String = when (val pref = refreshPref()) {
        "auto" -> "off"
        else -> if ((pref.toIntOrNull() ?: 0) >= (supportedRefreshRates().maxOrNull() ?: 60)) "off" else pref
    }

    private fun currentPageSettings() = PageSettings(
        renderScale = currentQuality().renderScale,
        frameCap = frameCap(),
        showFps = prefs.getBoolean("show_fps", false),
        displayHz = currentDisplay()?.refreshRate ?: 60f,
    )

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay

    /** Display modes at the current resolution: only the refresh rate differs between them. */
    private fun sameResolutionModes(): List<Display.Mode> {
        val display = currentDisplay() ?: return emptyList()
        val current = display.mode
        return display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
    }

    private fun supportedRefreshRates(): List<Int> =
        sameResolutionModes().map { it.refreshRate.roundToInt() }.distinct().sortedDescending()

    /**
     * Asks Android for a display mode. WebView animations run at whatever rate the window gets,
     * so this is what lets Folia draw at 120 fps. The system (and vendor policies such as HyperOS's
     * per-app refresh rules) still has the final say.
     */
    private fun applyRefreshRate() {
        val modes = sameResolutionModes()
        if (modes.isEmpty()) return
        val pref = refreshPref()
        val target = if (pref == "auto") {
            modes.maxByOrNull { it.refreshRate }
        } else {
            val hz = pref.toFloatOrNull() ?: 60f
            modes.filter { it.refreshRate <= hz + 1f }.maxByOrNull { it.refreshRate }
                ?: modes.minByOrNull { abs(it.refreshRate - hz) }
        }
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = target?.modeId ?: 0
            preferredRefreshRate = target?.refreshRate ?: 0f
        }
    }

    private fun refreshLabel(): String {
        val now = currentDisplay()?.refreshRate?.roundToInt()
        val chosen = when (val pref = refreshPref()) {
            "auto" -> "自動"
            else -> "$pref Hz"
        }
        return if (now != null) "$chosen（目前 $now Hz）" else chosen
    }

    private fun pickQuality() {
        val current = QUALITIES.indexOf(currentQuality()).coerceAtLeast(0)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("畫質")
            .setSingleChoiceItems(QUALITIES.map { "${it.label}：${it.detail}" }.toTypedArray(), current) { dialog, which ->
                prefs.edit().putString("quality", QUALITIES[which].id).apply()
                dialog.dismiss()
                loadFolia()
            }
            .setOnDismissListener { enterImmersive() }
            .show()
    }

    private fun pickRefreshRate() {
        val rates = supportedRefreshRates()
        val values = listOf("auto") + rates.map { it.toString() }
        val labels = listOf("自動（螢幕最高 ${rates.maxOrNull() ?: 60} Hz）") + rates.map { "$it Hz" }
        val current = values.indexOf(refreshPref()).coerceAtLeast(0)
        val builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("刷新率")
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                prefs.edit().putString("refresh", values[which]).apply()
                dialog.dismiss()
                applyRefreshRate()
                loadFolia()
            }
            .setOnDismissListener { enterImmersive() }
        if (rates.size <= 1) {
            builder.setMessage("這個螢幕只回報一種刷新率（${rates.firstOrNull() ?: 60} Hz），系統可能限制了這個 App。可到系統「顯示」設定把更新率調成最高或自訂後再試。")
                .setPositiveButton("知道了", null)
        }
        builder.show()
    }

    private fun onOff(key: String, default: Boolean) = if (prefs.getBoolean(key, default)) "開" else "關"

    private fun showSettings() {
        val mode = prefs.getString("mode", DEFAULT_MODE)
        val modeLabel = modeLabel(mode)
        val isFx = mode?.startsWith("fx:") == true
        val offset = prefs.getLong("offset", 0L)
        val items = arrayOf(
            "歌詞動畫：$modeLabel",
            "畫質：${currentQuality().label}",
            "刷新率：${refreshLabel()}",
            "顯示 FPS：${onOff("show_fps", false)}",
            "日間主題：${onOff("daylight", false)}",
            "副歌詞：${SUBTITLES.firstOrNull { it.first == subtitleMode() }?.second ?: "中文翻譯"}",
            "簡體歌詞轉繁體：${onOff("traditional", true)}",
            "歌詞時間偏移：${if (offset > 0) "+" else ""}${offset} ms",
            "保持螢幕常亮：${onOff("keep_screen_on", true)}",
            "本機歌詞資料夾：${LocalLyrics.folderLabel ?: "未設定"}",
            if (isFx) "動畫與外觀細節（Folia 動畫專用）" else "動畫與外觀細節（字型、背景、各動畫細部設定）",
            "跟著音樂律動：${onOff("audio_reactive", false)}",
            "手勢說明",
            "重新載入畫面",
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("設定")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickMode()
                    1 -> pickQuality()
                    2 -> pickRefreshRate()
                    3 -> { toggle("show_fps", false); loadFolia(); showSettings() }
                    4 -> { toggle("daylight", false); loadFolia(); showSettings() }
                    5 -> pickSubtitle()
                    6 -> { toggle("traditional", true); applyHubPrefs(); showSettings() }
                    7 -> pickOffset()
                    8 -> { toggle("keep_screen_on", true); applyKeepScreenOn(); showSettings() }
                    9 -> pickLyricsFolder()
                    10 -> if (isFx) {
                        android.widget.Toast.makeText(this, "這是 Folia 動畫的細節設定，流詞特效不需要調整", android.widget.Toast.LENGTH_LONG).show()
                        showSettings()
                    } else openTuning()
                    11 -> toggleAudio()
                    12 -> showGestureTutorial()
                    13 -> loadFolia()
                }
            }
            .setOnDismissListener { enterImmersive() }
            .show()
    }

    private fun toggle(key: String, default: Boolean) {
        prefs.edit().putBoolean(key, !prefs.getBoolean(key, default)).apply()
    }

    private fun pickMode() {
        val current = prefs.getString("mode", DEFAULT_MODE)
        val groups = listOf(
            Triple("流詞特效", "流詞自己的動態歌詞", FX_MODES),
            Triple("誇張特效", "爆炸、隕石、故障…好玩為主", WILD_MODES),
            Triple("更多動畫（Folia）", "Folia 原本內建的動畫", MODES),
        )
        val labels = groups.map { (name, desc, list) ->
            val mark = if (list.any { it.id == current }) "　● ${modeLabel(current)}" else ""
            "$name$mark\n$desc"
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("歌詞動畫")
            .setItems(labels.toTypedArray()) { _, which -> pickModeIn(groups[which].first, groups[which].third) }
            .setOnDismissListener { enterImmersive() }
            .show()
    }

    private fun pickModeIn(title: String, list: List<Mode>) {
        val current = list.indexOfFirst { it.id == prefs.getString("mode", DEFAULT_MODE) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(list.map { it.label }.toTypedArray(), current) { dialog, which ->
                prefs.edit().putString("mode", list[which].id).apply()
                dialog.dismiss()
                loadFolia()
            }
            .setNegativeButton("返回") { _, _ -> pickMode() }
            .setOnDismissListener { enterImmersive() }
            .show()
    }

    private fun subtitleMode(): String {
        val stored = prefs.getString("subtitle", null)
        if (stored != null) return stored
        // Before this setting existed there was an on/off translation switch.
        return if (prefs.getBoolean("translation", true)) "translation" else "none"
    }

    private fun pickSubtitle() {
        val current = SUBTITLES.indexOfFirst { it.first == subtitleMode() }.coerceAtLeast(0)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("副歌詞（歌詞下方那一行）")
            .setSingleChoiceItems(SUBTITLES.map { it.second }.toTypedArray(), current) { dialog, which ->
                prefs.edit().putString("subtitle", SUBTITLES[which].first).apply()
                dialog.dismiss()
                loadFolia()
            }
            .setOnDismissListener { enterImmersive() }
            .show()
    }

    private fun pickOffset() {
        val current = OFFSETS.indexOf(prefs.getLong("offset", 0L)).coerceAtLeast(0)
        val labels = OFFSETS.map {
            when {
                it == 0L -> "0 ms（不調整）"
                it > 0 -> "+$it ms（歌詞晚一點，藍牙耳機常用）"
                else -> "$it ms（歌詞早一點）"
            }
        }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("歌詞時間偏移")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs.edit().putLong("offset", OFFSETS[which]).apply()
                applyHubPrefs()
                dialog.dismiss()
            }
            .setOnDismissListener { enterImmersive() }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    /** Called from folia-bridge.js when Folia's (fake) socket opens. */
    inner class JsBridge {
        @JavascriptInterface
        fun tuningClosed() {
            handler.post { this@MainActivity.tuningClosed() }
        }

        @JavascriptInterface
        fun requestSnapshot() {
            handler.post {
                MediaHub.pushSnapshot()
                updateFoliaInset()
                // Applying the panel's changes reloads the page, which also closes it.
                tuningClosed()
            }
        }
    }
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
