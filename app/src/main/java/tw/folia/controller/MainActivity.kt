package tw.folia.controller

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity() {

    private data class Mode(val id: String, val label: String)

    companion object {
        // Folia's builtin lyric animations (src/types/visualizerModes.ts), with their Chinese names.
        private val MODES = listOf(
            Mode("classic", "流光"), Mode("cadenza", "心象"), Mode("partita", "雲階"),
            Mode("fume", "浮名"), Mode("monet", "莫奈"), Mode("cappella", "群唱"),
            Mode("tilt", "傾訴"), Mode("claddagh", "回環"), Mode("diorama", "鏡台"),
            Mode("pendolo", "時計"), Mode("sonnet", "商籟"), Mode("tempera", "凝彩"),
            Mode("lumiere", "繪光"), Mode("still", "靜止"),
        )
        private val OFFSETS = longArrayOf(-1000, -500, -250, 0, 250, 500, 750, 1000, 1500)
        private const val AUTO_HIDE_MS = 4000L
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var assetServer: FoliaAssetServer
    private lateinit var webView: WebView
    private lateinit var controls: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var statusText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var playButton: ImageButton
    private lateinit var permissionPanel: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var userSeeking = false
    private var resumed = false

    private val autoHide = Runnable { setControlsVisible(false) }
    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgressUi()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("folia_controller", MODE_PRIVATE)
        assetServer = FoliaAssetServer(this)
        MediaHub.init(this)
        applyHubPrefs()

        window.decorView.setBackgroundColor(Color.BLACK)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContentView(buildLayout())
        applyKeepScreenOn()
        enterImmersive()

        MediaHub.webSink = { json ->
            webView.evaluateJavascript("window.__foliaPush&&window.__foliaPush(${JSONObject.quote(json)})", null)
        }
        MediaHub.uiListener = { refreshUi() }
        loadFolia()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        webView.onResume()
        updatePermissionState()
        handler.post(progressTicker)
        showControlsBriefly()
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(progressTicker)
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

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun buildLayout(): View {
        val root = FrameLayout(this)
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

        // Transparent layer: a tap anywhere shows / hides the controls (Folia's page needs no touch).
        val tapLayer = View(this).apply {
            isClickable = true
            setOnClickListener { setControlsVisible(!controlsVisible) }
        }
        root.addView(tapLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        controls = buildControls()
        root.addView(controls, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        permissionPanel = buildPermissionPanel()
        root.addView(permissionPanel, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    private fun buildControls(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xE6000000.toInt(), 0x99000000.toInt(), 0x00000000),
            )
            isClickable = true // swallow taps so they don't reach the tap layer
        }

        titleText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setOnClickListener { openSourceApp() }
        }
        subtitleText = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setOnClickListener { openSourceApp() }
        }
        statusText = TextView(this).apply {
            setTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }
        panel.addView(titleText, LinearLayout.LayoutParams(MATCH, WRAP))
        panel.addView(subtitleText, LinearLayout.LayoutParams(MATCH, WRAP))
        panel.addView(statusText, LinearLayout.LayoutParams(MATCH, WRAP))

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        positionText = timeLabel()
        durationText = timeLabel()
        seekBar = SeekBar(this).apply {
            max = 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) positionText.text = formatTime(progress.toLong())
                }
                override fun onStartTrackingTouch(bar: SeekBar) {
                    userSeeking = true
                    handler.removeCallbacks(autoHide)
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    userSeeking = false
                    MediaHub.seekTo(bar.progress.toLong())
                    scheduleAutoHide()
                }
            })
        }
        seekRow.addView(positionText, LinearLayout.LayoutParams(dp(52), WRAP))
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, WRAP, 1f))
        seekRow.addView(durationText, LinearLayout.LayoutParams(dp(52), WRAP))
        panel.addView(seekRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { width = dp(720).coerceAtMost(resources.displayMetrics.widthPixels) })

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        buttonRow.addView(iconButton(android.R.drawable.ic_menu_preferences, 44) { showSettings() })
        buttonRow.addView(spacer())
        buttonRow.addView(iconButton(android.R.drawable.ic_media_previous, 56) { MediaHub.skipPrevious() })
        playButton = iconButton(android.R.drawable.ic_media_play, 72) { MediaHub.togglePlayPause() }
        buttonRow.addView(playButton)
        buttonRow.addView(iconButton(android.R.drawable.ic_media_next, 56) { MediaHub.skipNext() })
        buttonRow.addView(spacer())
        buttonRow.addView(iconButton(android.R.drawable.ic_menu_view, 44) { openSourceApp() })
        panel.addView(buttonRow, LinearLayout.LayoutParams(WRAP, WRAP))
        return panel
    }

    private fun timeLabel() = TextView(this).apply {
        setTextColor(0xCCFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        text = formatTime(0)
    }

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(20), 1) }

    private fun iconButton(icon: Int, sizeDp: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        background = null
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val pad = dp(sizeDp / 6)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setOnClickListener {
            onClick()
            scheduleAutoHide()
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
        val mode = prefs.getString("mode", "classic") ?: "classic"
        val url = buildString {
            append(FoliaAssetServer.ORIGIN).append("/?obs=1&obsSource=now-playing&host=android-bridge")
            append("&visualizer=").append(Uri.encode(mode))
            if (prefs.getBoolean("daylight", false)) append("&daylight=1")
        }
        webView.loadUrl(url)
    }

    private fun updatePermissionState() {
        val granted = MediaHub.hasNotificationAccess(this)
        permissionPanel.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted) {
            MediaHub.start()
            MediaHub.pushSnapshot()
        }
        refreshUi()
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
        subtitleText.text = listOfNotNull(info?.artist?.takeIf { it.isNotBlank() }, MediaHub.sourceAppLabel())
            .joinToString(" · ")
            .ifEmpty { "先在 Spotify 或 YouTube Music 播放一首歌" }
        statusText.text = MediaHub.lyricStatus
        playButton.setImageResource(if (MediaHub.isPlaying()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
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

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        handler.removeCallbacks(autoHide)
        controls.animate().cancel()
        if (visible) {
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(180).start()
            scheduleAutoHide()
        } else {
            controls.animate().alpha(0f).setDuration(300).withEndAction {
                if (!controlsVisible) controls.visibility = View.INVISIBLE
            }.start()
        }
    }

    private fun showControlsBriefly() = setControlsVisible(true)

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHide)
        if (controlsVisible && !userSeeking) handler.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    // ---------- settings ----------

    private fun applyHubPrefs() {
        MediaHub.includeTranslation = prefs.getBoolean("translation", true)
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

    private fun onOff(key: String, default: Boolean) = if (prefs.getBoolean(key, default)) "開" else "關"

    private fun showSettings() {
        handler.removeCallbacks(autoHide)
        val mode = prefs.getString("mode", "classic")
        val modeLabel = MODES.firstOrNull { it.id == mode }?.label ?: mode
        val offset = prefs.getLong("offset", 0L)
        val items = arrayOf(
            "歌詞動畫：$modeLabel",
            "日間主題：${onOff("daylight", false)}",
            "顯示翻譯：${onOff("translation", true)}",
            "簡體歌詞轉繁體：${onOff("traditional", true)}",
            "歌詞時間偏移：${if (offset > 0) "+" else ""}${offset} ms",
            "保持螢幕常亮：${onOff("keep_screen_on", true)}",
            "重新載入畫面",
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("設定")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickMode()
                    1 -> { toggle("daylight", false); loadFolia(); showSettings() }
                    2 -> { toggle("translation", true); applyHubPrefs(); showSettings() }
                    3 -> { toggle("traditional", true); applyHubPrefs(); showSettings() }
                    4 -> pickOffset()
                    5 -> { toggle("keep_screen_on", true); applyKeepScreenOn(); showSettings() }
                    6 -> loadFolia()
                }
            }
            .setOnDismissListener { enterImmersive(); scheduleAutoHide() }
            .show()
    }

    private fun toggle(key: String, default: Boolean) {
        prefs.edit().putBoolean(key, !prefs.getBoolean(key, default)).apply()
    }

    private fun pickMode() {
        val current = MODES.indexOfFirst { it.id == prefs.getString("mode", "classic") }.coerceAtLeast(0)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("歌詞動畫")
            .setSingleChoiceItems(MODES.map { "${it.label}（${it.id}）" }.toTypedArray(), current) { dialog, which ->
                prefs.edit().putString("mode", MODES[which].id).apply()
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
        fun requestSnapshot() {
            handler.post { MediaHub.pushSnapshot() }
        }
    }
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
