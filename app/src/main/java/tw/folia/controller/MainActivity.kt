package tw.folia.controller

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.SystemClock
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
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.Calendar
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
            Mode("fx:ink", "墨染"), Mode("fx:dive", "潛入"), Mode("fx:minimal", "極簡"),
            Mode("fx:karaoke", "卡拉OK"), Mode("fx:cinema", "電影字幕"), Mode("fx:ripple", "液面"),
            Mode("fx:stardust", "星塵"), Mode("fx:neon", "霓虹"), Mode("fx:vinyl", "黑膠"),
            Mode("fx:comic", "漫畫對白"), Mode("fx:terminal", "終端"), Mode("fx:danmaku", "彈幕"),
            Mode("fx:led", "LED 看板"),
        )
        private val WILD_MODES = listOf(
            Mode("fx:boom", "爆破"), Mode("fx:meteor", "隕石"), Mode("fx:glitch", "故障"),
            Mode("fx:vortex", "漩渦"), Mode("fx:fireworks", "煙火"), Mode("fx:jelly", "果凍"),
            Mode("fx:warp", "曲速"), Mode("fx:shatter", "碎玻璃"),
        )
        private const val DEFAULT_MODE = "fx:ink"
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
        private const val REQUEST_NOTIFY = 43
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
        registerReceiver(powerListener, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        if (intent.getBooleanExtra(DisplayModeService.EXTRA_LAUNCH, false)) wakeForDisplayMode()
        applyNightDim()

        MediaHub.webSink = { json ->
            webView.evaluateJavascript("window.__foliaPush&&window.__foliaPush(${JSONObject.quote(json)})", null)
        }
        MediaHub.uiListener = { refreshUi() }
        if (intent.getBooleanExtra("demo", false)) {
            MediaHub.startDemo(demoCover())
            // CI screenshots of the 動畫與外觀 panel.
            if (intent.getBooleanExtra("tuning", false)) handler.postDelayed({ openTuning() }, 18000)
            // CI screenshots of the glass dialogs.
            if (intent.getBooleanExtra("settings", false)) {
                intent.getStringExtra("settingsTab")?.let { prefs.edit().putString("settings_tab", it).apply() }
                handler.postDelayed({ showSettings() }, 14000)
            }
            if (intent.getBooleanExtra("floating", false)) handler.postDelayed({ setFloating(true) }, 4000)
            if (intent.getBooleanExtra("about", false)) handler.postDelayed({ showAbout() }, 14000)
        }
        loadFolia()
        refreshUi()
        if (!MediaHub.demo) handler.postDelayed({ autoCheckUpdate() }, 8000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(DisplayModeService.EXTRA_LAUNCH, false)) {
            wakeForDisplayMode()
            applyKeepScreenOn()
            applyNightDim()
        }
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
        FloatingLyricsService.appInFront = true
        resumeFloating()
        handler.removeCallbacks(standbyTicker)
        handler.post(standbyTicker)
        resumeDisplayMode()
        handler.removeCallbacks(dimTicker)
        handler.post(dimTicker)
    }

    override fun onPause() {
        resumed = false
        FloatingLyricsService.appInFront = false
        handler.removeCallbacks(standbyTicker)
        handler.removeCallbacks(dimTicker)
        audio.stop()
        handler.removeCallbacks(progressTicker)
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(powerListener) }
        downloadCancelled = true
        MediaHub.webSink = null
        MediaHub.uiListener = null
        // The floating lyrics keep reading the player after the app closes.
        if (isFinishing && !FloatingLyricsService.running) MediaHub.stop()
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
        card.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) updateFoliaInset()
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
            card.background = glassBackground(dpf(26f), palette.card, if (glassBlurOn()) 0x70 else 0xC8)
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
        glassDialog()
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
        refreshSettings()
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
            settingsRoot != null -> closeSettings()
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
            text = "流詞要讀取 Spotify、YouTube Music 等 App 正在播放的歌曲和進度，" +
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
        standbyShown = false
        // The card's see-through level follows the quality (no blur behind it in 流暢).
        appliedPalette = null
        if (::card.isInitialized) applyCardLook()
        // CI screenshots pick an effect for one run without touching the saved setting.
        val mode = currentMode()
        if (mode.startsWith("fx:")) {
            // Flowrics' own effect page; it reads the same feed, the 副歌詞 and 待機時鐘 settings.
            val cfg = JSONObject().put("subtitleContentMode", subtitleMode()).put("standby", prefs.getBoolean("standby", true))
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
        updateGlass()
    }

    // ---------- liquid glass ----------

    /**
     * The page blurs what's behind the info card (CSS backdrop-filter on an element at the card's
     * place, folia-bridge.js), so the native card on top can be see-through like glass. Off for the
     * 流暢 quality, where every bit of GPU goes to the animation.
     */
    private fun glassBlurOn() = currentQuality().id != "smooth"

    private fun updateGlass() {
        val scale = currentQuality().renderScale
        val on = glassBlurOn() && controlsVisible && card.width > 0
        val js = if (on) {
            String.format(
                Locale.US, "{on:true,x:%.1f,y:%.1f,w:%.1f,h:%.1f,r:%.1f,blur:%.1f}",
                card.left * scale, card.top * scale, card.width * scale, card.height * scale,
                dpf(26f) * scale, dpf(26f) * scale,
            )
        } else "{on:false}"
        webView.evaluateJavascript("window.__fcSetGlass&&window.__fcSetGlass($js)", null)
    }

    /** A glass pane: a tint, a sheen across the top and a bright rim. */
    private fun glassBackground(radius: Float, tint: Int, alpha: Int): android.graphics.drawable.Drawable {
        val base = tint and 0xFFFFFF
        val body = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(base or ((alpha * 0.8).toInt() shl 24), base or (alpha.coerceAtMost(255) shl 24)),
        ).apply { cornerRadius = radius }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x2EFFFFFF, 0x08FFFFFF, 0x00FFFFFF, 0x0AFFFFFF),
        ).apply { cornerRadius = radius }
        val rim = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), 0x4DFFFFFF)
        }
        val innerRim = GradientDrawable().apply {
            cornerRadius = radius - dpf(1f)
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), 0x14FFFFFF)
        }
        return LayerDrawable(arrayOf(body, sheen, rim, innerRim)).apply {
            val i = dp(1)
            setLayerInset(3, i, i, i, i)
        }
    }

    /** Dialogs as frosted glass: blurs what's behind them on Android 12+. */
    private fun glassDialog() = object : AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert) {
        override fun setOnDismissListener(listener: android.content.DialogInterface.OnDismissListener?): AlertDialog.Builder =
            super.setOnDismissListener { d ->
                listener?.onDismiss(d)
                refreshSettings()
            }

        override fun create(): AlertDialog {
            val dialog = super.create()
            dialog.window?.let { window ->
                var blur = false
                if (Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled) {
                    try {
                        // The blur goes on the window's decor view, which doesn't exist until asked for.
                        window.decorView
                        window.setBackgroundBlurRadius(dp(40))
                        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                        window.attributes = window.attributes.apply { blurBehindRadius = dp(12) }
                        blur = true
                    } catch (e: Exception) {
                        android.util.Log.w("Flowrics", "no dialog blur", e)
                    }
                }
                window.setBackgroundDrawable(
                    android.graphics.drawable.InsetDrawable(
                        glassBackground(dpf(28f), 0x1C1C22, if (blur) 0x99 else 0xF0), dp(16), dp(24), dp(16), dp(24),
                    ),
                )
                window.setDimAmount(0.3f)
            }
            return dialog
        }
    }

    /** The current Flowrics effect's own options (墨染's 排版 and 配色, …), read from the page. */
    private fun showFxOptions() {
        webView.evaluateJavascript("JSON.stringify(window.Flowrics&&Flowrics.options&&Flowrics.options())") { raw ->
            val info = try {
                val text = org.json.JSONArray("[$raw]").optString(0, "null")
                if (text == "null") null else JSONObject(text)
            } catch (e: Exception) {
                null
            }
            if (info == null) {
                android.widget.Toast.makeText(this, "這個特效沒有可以調整的選項", android.widget.Toast.LENGTH_SHORT).show()
                showSettings()
                return@evaluateJavascript
            }
            val options = info.getJSONArray("options")
            val labels = (0 until options.length()).map { i ->
                val o = options.getJSONObject(i)
                val choices = o.getJSONArray("choices")
                val current = (0 until choices.length()).map { choices.getJSONArray(it) }
                    .firstOrNull { it.getString(0) == o.optString("value") }?.getString(1)?.substringBefore("（") ?: ""
                "${o.getString("label")}：$current"
            }
            glassDialog()
                .setTitle("${info.optString("label")}・特效選項")
                .setItems(labels.toTypedArray()) { _, which -> pickFxOption(options.getJSONObject(which)) }
                .setNegativeButton("返回") { _, _ -> showSettings() }
                .setOnDismissListener { enterImmersive() }
                .show()
        }
    }

    private fun pickFxOption(option: JSONObject) {
        val choices = option.getJSONArray("choices")
        val values = (0 until choices.length()).map { choices.getJSONArray(it).getString(0) }
        val names = (0 until choices.length()).map { choices.getJSONArray(it).getString(1) }
        glassDialog()
            .setTitle(option.getString("label"))
            .setSingleChoiceItems(names.toTypedArray(), values.indexOf(option.optString("value"))) { dialog, which ->
                dialog.dismiss()
                webView.evaluateJavascript(
                    "Flowrics.setOption(${JSONObject.quote(option.getString("id"))},${JSONObject.quote(values[which])})",
                ) { refreshSettings() }
            }
            .setOnDismissListener { enterImmersive() }
            .show()
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        glassDialog()
            .setTitle("流詞 Flowrics")
            .setMessage(
                "版本 $version\n在任何播放器播歌時，顯示會動的歌詞。\n\n" +
                    "「Folia 動畫」來自 Folia（github.com/chthollyphile/folia-major），以 AGPL-3.0 授權。" +
                    "流詞含有 Folia，同樣依 AGPL-3.0 提供原始碼：github.com/EnWeiLo/folia-controller。\n\n" +
                    "歌詞與音樂的版權屬於各權利人。",
            )
            .setPositiveButton("原始碼") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/EnWeiLo/folia-controller")))
                } catch (e: ActivityNotFoundException) {
                }
            }
            .setNeutralButton("檢查更新") { _, _ -> checkUpdateNow() }
            .setNegativeButton("關閉", null)
            .setOnDismissListener { enterImmersive() }
            .show()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
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
        // 顯示器模式: stay awake only while charging, so an unplugged phone sleeps normally.
        val keep = if (prefs.getBoolean("display_mode", false)) DisplayModeService.isCharging(this)
        else prefs.getBoolean("keep_screen_on", true)
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------- local lyrics folder ----------

    private fun pickLyricsFolder() {
        val builder = glassDialog()
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
        val waiting = glassDialog()
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
        val builder = glassDialog()
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
        glassDialog()
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
        val builder = glassDialog()
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

    // ---------- settings: a glass sheet with tabs ----------

    private data class SettingsTab(val id: String, val label: String, val icon: Int)
    private val settingsTabs = listOf(
        SettingsTab("anim", "動畫", R.drawable.ic_set_anim),
        SettingsTab("display", "畫面", R.drawable.ic_set_display),
        SettingsTab("lyrics", "歌詞", R.drawable.ic_set_lyrics),
        SettingsTab("sound", "聲音", R.drawable.ic_set_sound),
        SettingsTab("other", "其他", R.drawable.ic_set_more),
    )
    private var settingsRoot: FrameLayout? = null
    private var settingsSheet: LinearLayout? = null
    private var settingsBody: LinearLayout? = null
    private var settingsScroll: ScrollView? = null
    private var settingsTabBar: LinearLayout? = null
    private var settingsTab = "anim"
    private val thumbCache = HashMap<String, Bitmap?>()

    private fun refreshSettings() {
        if (settingsRoot != null) renderSettings()
    }

    private fun showSettings() {
        if (settingsRoot != null) {
            renderSettings()
            return
        }
        settingsTab = prefs.getString("settings_tab", "anim") ?: "anim"
        val root = FrameLayout(this).apply {
            elevation = dpf(30f)
            setBackgroundColor(0x59000000)
            isClickable = true
            setOnClickListener { closeSettings() }
        }
        val metrics = resources.displayMetrics
        val w = (metrics.widthPixels - dp(24)).coerceAtMost(dp(600))
        val h = (metrics.heightPixels * 0.88f).toInt().coerceAtMost(dp(820))
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = glassBackground(dpf(28f), 0x1C1C22, if (glassBlurOn()) 0x99 else 0xF2)
            setPadding(0, dp(6), 0, dp(6))
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(10), dp(10), dp(6))
            addView(label(20f, Color.WHITE, bold = true).apply { text = "設定" }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(iconButton(R.drawable.ic_set_close, 40, 20, "關閉") { closeSettings() })
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dpf(999f)
                setColor(0x40000000)
                setStroke(dp(1), 0x1FFFFFFF)
            }
        }
        settingsTabs.forEach { tab ->
            tabs.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(5))
                tag = tab.id
                addView(ImageView(context).apply { setImageResource(tab.icon) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(label(11.5f, Color.WHITE).apply { text = tab.label; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
                setOnClickListener {
                    settingsTab = tab.id
                    settingsScroll?.scrollTo(0, 0)
                    renderSettings()
                }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(16))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        sheet.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        sheet.addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = dp(14); marginEnd = dp(14); bottomMargin = dp(6) })
        sheet.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(sheet, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
        rootLayout.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        settingsRoot = root
        settingsSheet = sheet
        settingsBody = body
        settingsScroll = scroll
        settingsTabBar = tabs
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(180).start()
        sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSheetGlass(true) }
        renderSettings()
    }

    private fun closeSettings() {
        val root = settingsRoot ?: return
        prefs.edit().putString("settings_tab", settingsTab).apply()
        updateSheetGlass(false)
        settingsRoot = null
        settingsSheet = null
        settingsBody = null
        settingsScroll = null
        settingsTabBar = null
        root.animate().alpha(0f).setDuration(160).withEndAction { rootLayout.removeView(root) }.start()
        thumbCache.clear()
        enterImmersive()
    }

    /** The page blurs what's behind the sheet, like it does for the info card. */
    private fun updateSheetGlass(on: Boolean) {
        val sheet = settingsSheet
        val scale = currentQuality().renderScale
        val js = if (on && sheet != null && glassBlurOn() && sheet.width > 0) {
            String.format(
                Locale.US, "{id:'sheet',on:true,x:%.1f,y:%.1f,w:%.1f,h:%.1f,r:%.1f,blur:%.1f}",
                sheet.left * scale, sheet.top * scale, sheet.width * scale, sheet.height * scale,
                dpf(28f) * scale, dpf(30f) * scale,
            )
        } else "{id:'sheet',on:false}"
        webView.evaluateJavascript("window.__fcSetGlass&&window.__fcSetGlass($js)", null)
    }

    private fun renderSettings() {
        val body = settingsBody ?: return
        settingsTabBar?.let { bar ->
            for (i in 0 until bar.childCount) {
                val child = bar.getChildAt(i)
                val on = child.tag == settingsTab
                child.background = if (on) GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40FFFFFF, 0x18FFFFFF),
                ).apply { cornerRadius = dpf(999f); setStroke(dp(1), 0x33FFFFFF) } else null
                child.alpha = if (on) 1f else 0.6f
            }
        }
        body.removeAllViews()
        val mode = currentMode()
        val isFx = mode.startsWith("fx:")
        when (settingsTab) {
            "anim" -> {
                if (isFx) {
                    val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    body.addView(holder)
                    fillFxOptions(holder)
                } else {
                    body.addView(settingRow(R.drawable.ic_set_sliders, "動畫與外觀細節", null, "每種動畫的細部設定、字型、副歌詞區、背景") {
                        closeSettings()
                        openTuning()
                    })
                }
                listOf(
                    Triple("Folia 動畫", MODES, "Folia 內建的歌詞動畫"),
                    Triple("流詞特效", FX_MODES, "流詞自己做的動態歌詞"),
                    Triple("誇張特效", WILD_MODES, "好玩為主"),
                ).forEach { (title, list, _) ->
                    body.addView(sectionLabel(title))
                    body.addView(modeGrid(list, mode))
                }
            }
            "display" -> {
                body.addView(settingRow(R.drawable.ic_set_gauge, "畫質", currentQuality().label, currentQuality().detail) { pickQuality() })
                body.addView(settingRow(R.drawable.ic_set_rate, "刷新率", refreshLabel(), null) { pickRefreshRate() })
                body.addView(switchRow(R.drawable.ic_set_screen, "保持螢幕常亮", "播歌時螢幕不會自動關掉", prefs.getBoolean("keep_screen_on", true)) {
                    toggle("keep_screen_on", true)
                    applyKeepScreenOn()
                })
                body.addView(switchRow(R.drawable.ic_set_clock, "待機時鐘", "沒在播歌 30 秒後，用墨染寫出時間；一播歌就回到歌詞", prefs.getBoolean("standby", true)) {
                    toggle("standby", true)
                    val on = prefs.getBoolean("standby", true)
                    webView.evaluateJavascript("window.Flowrics&&Flowrics.S&&(Flowrics.S.standbyEnabled=$on)", null)
                    checkStandby()
                })
                if (!isFx) {
                    body.addView(switchRow(R.drawable.ic_set_sun, "日間主題", "Folia 動畫的淺色版本", prefs.getBoolean("daylight", false)) {
                        toggle("daylight", false)
                        loadFolia()
                    })
                }
                body.addView(switchRow(R.drawable.ic_set_sliders, "顯示 FPS", "左上角顯示每秒畫出幾幀", prefs.getBoolean("show_fps", false)) {
                    toggle("show_fps", false)
                    loadFolia()
                })
                body.addView(sectionLabel("顯示器模式（舊手機當歌詞顯示器）"))
                body.addView(switchRow(R.drawable.ic_set_plug, "顯示器模式", "插上充電線就自動打開流詞並保持亮屏；拔掉後螢幕恢復正常熄屏。手機重新開機後也會自動待命（鎖定畫面上也會顯示）", prefs.getBoolean("display_mode", false)) {
                    setDisplayMode(!prefs.getBoolean("display_mode", false))
                })
                body.addView(switchRow(R.drawable.ic_set_moon, "夜間自動調暗", "充電顯示期間，晚上 22:00～06:00 把螢幕亮度降到很低", prefs.getBoolean("night_dim", true)) {
                    toggle("night_dim", true)
                    applyNightDim()
                })
                body.addView(settingRow(R.drawable.ic_set_palette, "套用顯示器外觀", "墨染・OLED 純黑", "全黑背景＋純白字，OLED 螢幕省電不烙印；並開啟待機時鐘") { applyDisplayPreset() })
            }
            "lyrics" -> {
                val offset = prefs.getLong("offset", 0L)
                body.addView(settingRow(R.drawable.ic_set_caption, "副歌詞", SUBTITLES.firstOrNull { it.first == subtitleMode() }?.second ?: "中文翻譯", "歌詞下方那一行") { pickSubtitle() })
                body.addView(switchRow(R.drawable.ic_set_trad, "簡體歌詞轉繁體", "日文歌詞不會轉換", prefs.getBoolean("traditional", true)) {
                    toggle("traditional", true)
                    applyHubPrefs()
                })
                body.addView(settingRow(R.drawable.ic_set_timer, "歌詞時間偏移", "${if (offset > 0) "+" else ""}$offset ms", "戴藍牙耳機時歌詞偏早，可以調成 +250～+500") { pickOffset() })
                body.addView(settingRow(R.drawable.ic_set_music, "換歌詞", MediaHub.lyricStatus.ifBlank { null }, "從所有來源挑一份，選過的會記住") {
                    closeSettings()
                    showLyricPicker()
                })
                body.addView(settingRow(R.drawable.ic_set_folder, "本機歌詞資料夾", LocalLyrics.folderLabel ?: "未設定", null) { pickLyricsFolder() })
                body.addView(sectionLabel("懸浮歌詞"))
                body.addView(switchRow(R.drawable.ic_set_float, "懸浮歌詞", "在其他 App 上方顯示兩行歌詞（歌詞＋翻譯）", prefs.getBoolean("floating_lyrics", false)) {
                    setFloating(!prefs.getBoolean("floating_lyrics", false))
                })
                body.addView(switchRow(R.drawable.ic_set_lock, "鎖定懸浮歌詞", "鎖定後手指會穿過歌詞；也可以從通知切換", prefs.getBoolean("float_locked", false)) {
                    if (FloatingLyricsService.running) {
                        startService(Intent(this, FloatingLyricsService::class.java).setAction(FloatingLyricsService.ACTION_LOCK))
                        handler.postDelayed({ refreshSettings() }, 150)
                    } else {
                        toggle("float_locked", false)
                    }
                })
            }
            "sound" -> {
                body.addView(switchRow(R.drawable.ic_set_wave, "跟著音樂律動", "讀取正在播放的聲音頻譜，讓有律動效果的動畫跟著節奏動；不錄音、不上傳", prefs.getBoolean("audio_reactive", false)) {
                    toggleAudio()
                })
            }
            else -> {
                body.addView(settingRow(R.drawable.ic_set_gesture, "手勢說明", null, null) {
                    closeSettings()
                    showGestureTutorial()
                })
                body.addView(settingRow(R.drawable.ic_set_reload, "重新載入畫面", null, null) { loadFolia() })
                body.addView(settingRow(R.drawable.ic_set_update, "檢查更新", "版本 ${versionName()}", "有新版本時，下載並安裝（需要確認一次）") { checkUpdateNow() })
                body.addView(settingRow(R.drawable.ic_set_info, "關於流詞", null, null) { showAbout() })
            }
        }
    }

    private fun sectionLabel(text: String) = label(12.5f, 0x8CFFFFFF.toInt()).apply {
        this.text = text
        setPadding(dp(22), dp(16), dp(22), dp(6))
    }

    private fun rowShell(icon: Int, title: String, desc: String?): Pair<LinearLayout, LinearLayout> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(20), dp(10), dp(14), dp(10))
            isClickable = true
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, android.graphics.drawable.ColorDrawable(Color.WHITE),
            )
        }
        row.addView(ImageView(this).apply {
            setImageResource(icon)
            alpha = 0.8f
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(15.5f, Color.WHITE).apply { text = title; maxLines = 2 })
        if (!desc.isNullOrBlank()) {
            texts.addView(label(12f, 0x8CFFFFFF.toInt()).apply {
                text = desc
                maxLines = 3
                ellipsize = null
                setPadding(0, dp(3), 0, 0)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        return Pair(row, texts)
    }

    private fun settingRow(icon: Int, title: String, value: String?, desc: String?, onClick: () -> Unit): View {
        val (row, _) = rowShell(icon, title, desc)
        if (!value.isNullOrBlank()) {
            row.addView(label(13.5f, 0xB3FFFFFF.toInt()).apply {
                text = value
                gravity = Gravity.END
                maxWidth = dp(170)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
        }
        row.addView(ImageView(this).apply { setImageResource(R.drawable.ic_set_chevron); alpha = 0.4f },
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) })
        row.setOnClickListener { onClick() }
        return row
    }

    private fun switchRow(icon: Int, title: String, desc: String?, checked: Boolean, onToggle: () -> Unit): View {
        val (row, _) = rowShell(icon, title, desc)
        val sw = android.widget.Switch(this).apply {
            isChecked = checked
            isClickable = false
            isFocusable = false
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(Color.WHITE, 0xFFE4E4E7.toInt()))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(0xFF34C759.toInt(), 0x66FFFFFF))
        }
        row.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
        row.setOnClickListener {
            sw.isChecked = !sw.isChecked
            onToggle()
            handler.postDelayed({ refreshSettings() }, 250)
        }
        return row
    }

    private fun thumbFile(id: String) = "folia/thumbs/" + (if (id.startsWith("fx:")) "fx-" + id.removePrefix("fx:") else id) + ".jpg"

    /** A grid of pictures, one per animation; tapping one switches to it (the sheet stays open). */
    private fun modeGrid(list: List<Mode>, current: String): View {
        val sheetW = settingsSheet?.layoutParams?.width ?: (resources.displayMetrics.widthPixels - dp(24))
        val inner = sheetW - dp(36)
        val columns = (inner / dp(128)).coerceIn(2, 5)
        val gap = dp(10)
        val tileW = (inner - gap * (columns - 1)) / columns
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(8))
        }
        var row: LinearLayout? = null
        list.forEachIndexed { i, m ->
            if (i % columns == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (i > 0) topMargin = gap })
            }
            val selected = m.id == current
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, intArrayOf(0x806E64C8.toInt(), 0x99282838.toInt()),
                )
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dpf(12f))
                    }
                }
                clipToOutline = true
                if (selected) foreground = GradientDrawable().apply {
                    cornerRadius = dpf(12f)
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(3), Color.WHITE)
                }
            }
            loadThumb(m.id, image)
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                addView(image, LinearLayout.LayoutParams(tileW, tileW * 9 / 16))
                addView(label(13f, if (selected) Color.WHITE else 0xB3FFFFFF.toInt(), bold = selected).apply {
                    text = m.label
                    gravity = Gravity.CENTER
                    setPadding(0, dp(5), 0, 0)
                }, LinearLayout.LayoutParams(tileW, WRAP))
                contentDescription = m.label
                setOnClickListener {
                    if (m.id != currentMode()) {
                        prefs.edit().putString("mode", m.id).apply()
                        loadFolia()
                    }
                    renderSettings()
                }
            }
            row?.addView(tile, LinearLayout.LayoutParams(tileW, WRAP).apply { if (i % columns > 0) marginStart = gap })
        }
        return grid
    }

    private fun loadThumb(id: String, view: ImageView) {
        if (thumbCache.containsKey(id)) {
            thumbCache[id]?.let { view.setImageBitmap(it) }
            return
        }
        val path = thumbFile(id)
        Thread {
            val bitmap = try {
                assets.open(path).use {
                    android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })
                }
            } catch (e: Exception) {
                null
            }
            handler.post {
                if (settingsRoot == null) return@post
                thumbCache[id] = bitmap
                if (bitmap != null) view.setImageBitmap(bitmap)
            }
        }.start()
    }

    /** The effect's own options (墨染's 排版、配色、字體…), read from the page. */
    private fun fillFxOptions(holder: LinearLayout) {
        webView.evaluateJavascript("JSON.stringify(window.Flowrics&&Flowrics.options&&Flowrics.options())") { raw ->
            if (settingsRoot == null) return@evaluateJavascript
            val info = try {
                val text = org.json.JSONArray("[$raw]").optString(0, "null")
                if (text == "null") null else JSONObject(text)
            } catch (e: Exception) {
                null
            }
            if (info == null) return@evaluateJavascript
            holder.addView(sectionLabel("特效選項・" + info.optString("label")))
            val options = info.getJSONArray("options")
            for (i in 0 until options.length()) {
                val o = options.getJSONObject(i)
                val choices = o.getJSONArray("choices")
                val current = (0 until choices.length()).map { choices.getJSONArray(it) }
                    .firstOrNull { it.getString(0) == o.optString("value") }?.getString(1).orEmpty()
                val icon = when (o.optString("id")) {
                    "layout" -> R.drawable.ic_set_lyrics
                    "write" -> R.drawable.ic_set_anim
                    "theme" -> R.drawable.ic_set_palette
                    "zhFont", "jaFont" -> R.drawable.ic_set_trad
                    "splash" -> R.drawable.ic_set_wave
                    "sign" -> R.drawable.ic_set_info
                    else -> R.drawable.ic_set_sliders
                }
                holder.addView(settingRow(icon, o.getString("label"), current.substringBefore("（"),
                    Regex("（(.*)）$").find(current)?.groupValues?.get(1)) { pickFxOption(o) })
            }
        }
    }

    // ---------- 懸浮歌詞 ----------

    private fun setFloating(on: Boolean) {
        if (!on) {
            prefs.edit().putBoolean("floating_lyrics", false).putBoolean("floating_pending", false).apply()
            FloatingLyricsService.stop(this)
            return
        }
        if (!FloatingLyricsService.canDraw(this)) {
            glassDialog()
                .setTitle("懸浮歌詞")
                .setMessage("要在其他 App 上方顯示歌詞，需要開啟「顯示在其他應用程式上層」權限。按「前往設定」後把流詞打開，再回到這裡。")
                .setPositiveButton("前往設定") { _, _ ->
                    prefs.edit().putBoolean("floating_pending", true).apply()
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (_: ActivityNotFoundException) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    }
                }
                .setNegativeButton("取消", null)
                .setOnDismissListener { enterImmersive() }
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFY)
        }
        prefs.edit().putBoolean("floating_lyrics", true).putBoolean("floating_pending", false).apply()
        FloatingLyricsService.start(this)
    }

    /** On return to the app: finish turning the floating lyrics on, or bring them back after a restart. */
    private fun resumeFloating() {
        if (prefs.getBoolean("floating_pending", false) && FloatingLyricsService.canDraw(this)) setFloating(true)
        else if (prefs.getBoolean("floating_lyrics", false) && FloatingLyricsService.canDraw(this) && !FloatingLyricsService.running) {
            FloatingLyricsService.start(this)
        }
        refreshSettings()
    }

    // ---------- 自動更新 ----------

    private var downloadCancelled = false
    private var updateBusy = false

    private fun autoCheckUpdate() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("update_checked", 0L) < 6 * 3600_000L) return
        prefs.edit().putLong("update_checked", now).apply()
        Updater.check(this) { release, _ ->
            if (release != null && prefs.getInt("update_skipped", 0) != release.build && !isFinishing) offerUpdate(release)
        }
    }

    private fun checkUpdateNow() {
        if (updateBusy) return
        Toast.makeText(this, "正在檢查更新…", Toast.LENGTH_SHORT).show()
        Updater.check(this) { release, reached ->
            when {
                isFinishing -> {}
                release != null -> offerUpdate(release)
                reached -> Toast.makeText(this, "已經是最新版本（${versionName()}）", Toast.LENGTH_LONG).show()
                else -> Toast.makeText(this, "連不上 GitHub，稍後再試", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun offerUpdate(release: Updater.Release) {
        if (updateBusy) return
        closeSettings()
        glassDialog()
            .setTitle("流詞有新版本")
            .setMessage("1.0.${release.build} 已經推出（目前 ${versionName()}）。\n\n按「立即更新」會下載新版本，然後跳出系統的安裝畫面，確認一下就完成；設定都會保留。")
            .setPositiveButton("立即更新") { _, _ -> startUpdate(release) }
            .setNegativeButton("稍後") { _, _ -> prefs.edit().putInt("update_skipped", release.build).apply() }
            .setOnDismissListener { enterImmersive() }
            .show()
    }

    private fun startUpdate(release: Updater.Release) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            glassDialog()
                .setTitle("需要允許安裝")
                .setMessage("第一次更新時，要允許流詞「安裝未知應用程式」。按「前往設定」把流詞打開，回來再按一次更新。")
                .setPositiveButton("前往設定") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } catch (_: ActivityNotFoundException) {
                    }
                }
                .setNegativeButton("取消", null)
                .setOnDismissListener { enterImmersive() }
                .show()
            return
        }
        updateBusy = true
        downloadCancelled = false
        val dialog = glassDialog()
            .setTitle("正在下載 1.0.${release.build}")
            .setMessage("0%")
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> downloadCancelled = true }
            .setOnDismissListener { enterImmersive() }
            .show()
        Updater.download(this, release, { percent -> dialog.setMessage("$percent%") }, { downloadCancelled }) { file ->
            updateBusy = false
            runCatching { dialog.dismiss() }
            when {
                downloadCancelled -> {}
                file == null -> Toast.makeText(this, "下載失敗，稍後再試", Toast.LENGTH_LONG).show()
                !Updater.install(this, file) -> Toast.makeText(this, "無法開始安裝", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- 顯示器模式 ----------

    private val powerListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            applyKeepScreenOn()
            applyNightDim()
            if (intent.action == Intent.ACTION_POWER_DISCONNECTED) {
                // Back to a normal app: no longer shown over the lock screen.
                @Suppress("DEPRECATION")
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
    }

    private val dimTicker = object : Runnable {
        override fun run() {
            applyNightDim()
            handler.postDelayed(this, 60_000)
        }
    }

    /** Started by plugging in the charger: light the screen, even over the lock screen. */
    @Suppress("DEPRECATION")
    private fun wakeForDisplayMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        handler.postDelayed({ window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) }, 10_000)
    }

    /** While 顯示器模式 is charging, the screen goes very dim between 22:00 and 06:00. */
    private fun applyNightDim() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val night = prefs.getBoolean("display_mode", false) && prefs.getBoolean("night_dim", true) &&
            DisplayModeService.isCharging(this) && (hour >= 22 || hour < 6)
        val target = if (night) 0.04f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (window.attributes.screenBrightness != target) {
            window.attributes = window.attributes.apply { screenBrightness = target }
        }
    }

    private fun setDisplayMode(on: Boolean) {
        if (!on) {
            prefs.edit().putBoolean("display_mode", false).putBoolean("display_pending", false).apply()
            DisplayModeService.stop(this)
            applyKeepScreenOn()
            applyNightDim()
            refreshSettings()
            return
        }
        if (!DisplayModeService.canDraw(this)) {
            glassDialog()
                .setTitle("顯示器模式")
                .setMessage("要在插上充電線時自動打開流詞，需要開啟「顯示在其他應用程式上層」權限。按「前往設定」把流詞打開，再回到這裡。")
                .setPositiveButton("前往設定") { _, _ ->
                    prefs.edit().putBoolean("display_pending", true).apply()
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (_: ActivityNotFoundException) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    }
                }
                .setNegativeButton("取消", null)
                .setOnDismissListener { enterImmersive() }
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFY)
        }
        prefs.edit().putBoolean("display_mode", true).putBoolean("display_pending", false).apply()
        DisplayModeService.start(this)
        applyKeepScreenOn()
        applyNightDim()
        refreshSettings()
    }

    /** On return to the app: finish turning 顯示器模式 on, or bring its waiting service back. */
    private fun resumeDisplayMode() {
        if (prefs.getBoolean("display_pending", false) && DisplayModeService.canDraw(this)) setDisplayMode(true)
        else if (prefs.getBoolean("display_mode", false) && DisplayModeService.canDraw(this) && !DisplayModeService.running) {
            DisplayModeService.start(this)
        }
        applyKeepScreenOn()
        applyNightDim()
    }

    /** 墨染 in the OLED black theme with the standby clock: the look for an old phone that only shows lyrics. */
    private fun applyDisplayPreset() {
        prefs.edit().putString("mode", "fx:ink").putBoolean("standby", true).apply()
        closeSettings()
        loadFolia()
        val js = "(function(){var F=window.Flowrics;if(F&&F.S&&F.S.fxId==='ink'){F.setOption('theme','oled');return 1}return 0})()"
        listOf(2500L, 5000L, 9000L).forEach { delay ->
            handler.postDelayed({ webView.evaluateJavascript(js, null) }, delay)
        }
        Toast.makeText(this, "已套用：墨染・OLED 純黑＋待機時鐘", Toast.LENGTH_LONG).show()
    }

    // ---------- 待機時鐘 ----------

    private var idleSince = 0L
    private var standbyShown = false
    private val standbyTicker = object : Runnable {
        override fun run() {
            checkStandby()
            handler.postDelayed(this, 5000)
        }
    }

    private fun currentMode(): String =
        (if (MediaHub.demo) intent.getStringExtra("mode") else null) ?: prefs.getString("mode", DEFAULT_MODE) ?: DEFAULT_MODE

    /**
     * Folia's pages switch to the clock page after 30 s without music and back when it plays;
     * Flowrics' own pages show the clock by themselves.
     */
    private fun checkStandby() {
        val folia = !currentMode().startsWith("fx:")
        val enabled = prefs.getBoolean("standby", true)
        val playing = MediaHub.isPlaying() && MediaHub.track != null
        val now = SystemClock.elapsedRealtime()
        if (playing) idleSince = 0L else if (idleSince == 0L) idleSince = now
        if (standbyShown) {
            if (playing || !enabled || !folia) loadFolia()
            return
        }
        if (folia && enabled && !playing && now - idleSince > 30_000 && settingsRoot == null && !tuningOpen && tutorial == null) {
            standbyShown = true
            val cfg = JSONObject().put("subtitleContentMode", subtitleMode()).put("standby", true)
            webView.loadUrl(FoliaAssetServer.ORIGIN + "/fx.html?fx=clock&host=android-bridge&cfg=" + Uri.encode(cfg.toString()))
        }
    }

    private fun toggle(key: String, default: Boolean) {
        prefs.edit().putBoolean(key, !prefs.getBoolean(key, default)).apply()
    }

    private fun pickMode() {
        val current = prefs.getString("mode", DEFAULT_MODE)
        val groups = listOf(
            Triple("Folia 動畫", "Folia 內建的 14 種歌詞動畫", MODES),
            Triple("流詞特效", "流詞自己做的動態歌詞", FX_MODES),
            Triple("誇張特效", "爆炸、隕石、故障…好玩為主", WILD_MODES),
        )
        val labels = groups.map { (name, desc, list) ->
            val mark = if (list.any { it.id == current }) "　● ${modeLabel(current)}" else ""
            "$name$mark\n$desc"
        }
        glassDialog()
            .setTitle("歌詞動畫")
            .setItems(labels.toTypedArray()) { _, which -> pickModeIn(groups[which].first, groups[which].third) }
            .setOnDismissListener { enterImmersive() }
            .show()
    }

    private fun pickModeIn(title: String, list: List<Mode>) {
        val current = list.indexOfFirst { it.id == prefs.getString("mode", DEFAULT_MODE) }
        glassDialog()
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
        glassDialog()
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
        glassDialog()
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
