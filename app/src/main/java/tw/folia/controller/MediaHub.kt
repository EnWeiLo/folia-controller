package tw.folia.controller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

data class TrackInfo(
    val key: String,
    /** Spotify track id, from the player's `spotify:track:<id>` media id. */
    val spotifyId: String? = null,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val packageName: String,
)

/**
 * Follows the media session of whichever app is playing (Spotify, YouTube Music, ...),
 * looks up its lyrics, and feeds Folia the same events the desktop "Now Playing" service
 * sends: Track, Lyric, PlayerPauseState, PlayerProgress.
 * Everything here runs on the main thread except cover encoding and lyric lookups.
 */
object MediaHub {
    private val main = Handler(Looper.getMainLooper())
    private val coverExecutor = Executors.newSingleThreadExecutor()
    private val lyricExecutor = Executors.newSingleThreadExecutor()

    private lateinit var appContext: Context
    private var sessionManager: MediaSessionManager? = null
    private var started = false
    private val registered = LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var controllers: List<MediaController> = emptyList()

    var current: MediaController? = null
        private set
    var track: TrackInfo? = null
        private set
    var lyricStatus: String = ""
        private set

    private var coverDataUrl: String? = null
    private var coverSource: Bitmap? = null

    /** The cover for the info card (small copy), and the card colours taken from it. */
    var coverBitmap: Bitmap? = null
        private set
    var palette: CoverPalette = CoverPalette.NEUTRAL
        private set
    private var lyricResult: LyricResult? = null

    @Volatile
    private var lyricRequestId = 0

    /** Receives one JSON envelope per event; MainActivity forwards it into the WebView. */
    var webSink: ((String) -> Unit)? = null
    var uiListener: (() -> Unit)? = null

    var convertToTraditional = true
        set(value) {
            if (field != value) { field = value; lyricContainer = null; pushLyric() }
        }

    /** Every lyric match found for the current song, best first (for the manual picker). */
    var lyricCandidates: List<LyricCandidate> = emptyList()
        private set
    private var candidatesSearched = false
    var noLyricsChosen = false
        private set
    val lyricChoiceRemembered: Boolean get() = currentQuery()?.let { LyricChoices.has(it) } == true
    val currentLyricCandidate: LyricCandidate? get() = lyricResult?.candidate
    private var lyricContainer: String? = null
    /** Shifts lyrics against the audio: positive = lyrics later (e.g. Bluetooth delay). */
    var lyricOffsetMs = 0L
        set(value) {
            if (field != value) { field = value; pushProgress() }
        }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        onSessionsChanged(list ?: emptyList())
    }

    // Folia runs its own clock from the last progress we sent. Every couple of seconds compare
    // where that clock should be with where the player really is, and re-anchor only on real drift:
    // each re-anchor is a tiny jump, so doing it on a timer showed up as a periodic hitch.
    private const val DRIFT_THRESHOLD_MS = 120L
    private var lastSentClockMs = 0L
    private var lastSentAtMs = 0L
    private var lastSentPlaying = false

    private val resync = object : Runnable {
        override fun run() {
            if (isPlaying()) {
                val folia = lastSentClockMs + if (lastSentPlaying) SystemClock.elapsedRealtime() - lastSentAtMs else 0L
                if (kotlin.math.abs(lyricClockMs() - folia) > DRIFT_THRESHOLD_MS) pushProgress()
            }
            main.postDelayed(this, 2000)
        }
    }

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
        LyricChoices.init(context)
        LocalLyrics.init(context)
    }

    private fun listenerComponent() = ComponentName(appContext, MediaListenerService::class.java)

    fun hasNotificationAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, MediaListenerService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun start() {
        if (!::appContext.isInitialized || demo) return
        if (started) {
            refreshSessions()
            return
        }
        val msm = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(), main)
            sessionManager = msm
            started = true
            onSessionsChanged(msm.getActiveSessions(listenerComponent()))
            main.removeCallbacks(resync)
            main.postDelayed(resync, 2000)
        } catch (e: SecurityException) {
            started = false
        }
    }

    fun stop() {
        main.removeCallbacks(resync)
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionManager = null
        registered.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        registered.clear()
        controllers = emptyList()
        current = null
        started = false
    }

    fun onListenerConnected() {
        main.post { if (started) refreshSessions() else start() }
    }

    private fun refreshSessions() {
        val msm = sessionManager ?: return
        try {
            onSessionsChanged(msm.getActiveSessions(listenerComponent()))
        } catch (_: SecurityException) {
        }
    }

    private fun onSessionsChanged(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        val iterator = registered.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in tokens) {
                entry.value.first.unregisterCallback(entry.value.second)
                iterator.remove()
            }
        }
        for (controller in list) {
            if (registered.containsKey(controller.sessionToken)) continue
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = onControllerPlayback(controller)
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    if (isCurrent(controller)) onMetadata(force = false)
                }
                override fun onSessionDestroyed() = refreshSessions()
            }
            controller.registerCallback(callback, main)
            registered[controller.sessionToken] = controller to callback
        }
        controllers = list.mapNotNull { registered[it.sessionToken]?.first }
        selectCurrent()
    }

    /** The playing session wins; otherwise keep the one we had; otherwise the most recent. */
    private fun selectCurrent() {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val kept = current?.let { cur -> controllers.firstOrNull { it.sessionToken == cur.sessionToken } }
        val next = playing ?: kept ?: controllers.firstOrNull()
        if (next?.sessionToken != current?.sessionToken) {
            current = next
            onMetadata(force = true)
        }
        onPlayback()
    }

    private fun isCurrent(controller: MediaController) = controller.sessionToken == current?.sessionToken

    private fun onControllerPlayback(controller: MediaController) {
        if (isCurrent(controller)) {
            onPlayback()
        } else if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            selectCurrent()
        }
    }

    private fun onMetadata(force: Boolean) {
        val controller = current
        val metadata = controller?.metadata
        if (controller == null || metadata == null) {
            if (track != null || force) {
                track = null
                coverDataUrl = null
                coverSource = null
                coverBitmap = null
                palette = CoverPalette.NEUTRAL
                lyricResult = null
                lyricContainer = null
                lyricCandidates = emptyList()
                lyricStatus = ""
                lyricRequestId++
                pushTrack()
                pushLyric()
                notifyUi()
            }
            return
        }

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        val previous = track
        val spotifyId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?.let { Regex("""spotify:track:([A-Za-z0-9]{22})""").find(it)?.groupValues?.get(1) }
        val next = TrackInfo("${controller.packageName}|$title|$artist", spotifyId, title, artist, album, duration, controller.packageName)
        val songChanged = previous?.key != next.key
        track = next

        if (songChanged) {
            coverDataUrl = null
            coverSource = null
            coverBitmap = null
            palette = CoverPalette.NEUTRAL
            lyricResult = null
            lyricContainer = null
            lyricCandidates = emptyList()
            startLyricLookup(next)
        } else if (previous?.durationMs == 0L && duration > 0 && lyricResult == null) {
            // Some players publish the length a moment later; it makes matching far more reliable.
            startLyricLookup(next)
        }
        if (art != null && !sameBitmap(art, coverSource)) {
            coverSource = art
            encodeCover(art, next.key)
        }
        if (songChanged || previous != next || force) pushTrack()
        if (songChanged) pushLyric()
        notifyUi()
    }

    private fun sameBitmap(a: Bitmap, b: Bitmap?): Boolean {
        if (b == null) return false
        if (a === b) return true
        return try {
            a.width == b.width && a.height == b.height && a.sameAs(b)
        } catch (_: Exception) {
            false
        }
    }

    private fun encodeCover(bitmap: Bitmap, key: String) {
        coverExecutor.execute {
            var thumb: Bitmap? = null
            var colours = CoverPalette.NEUTRAL
            val url = try {
                val maxSide = 600
                val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                } else bitmap
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                thumb = scaledCopy(scaled, 256)
                val tiny = Bitmap.createScaledBitmap(scaled, 32, 32, true)
                val pixels = IntArray(32 * 32)
                tiny.getPixels(pixels, 0, 32, 0, 0, 32, 32)
                colours = CoverPalette.from(pixels)
                "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } catch (_: Throwable) {
                null
            }
            main.post {
                if (url != null && track?.key == key && url != coverDataUrl) {
                    coverDataUrl = url
                    coverBitmap = thumb
                    palette = colours
                    pushTrack()
                    notifyUi()
                }
            }
        }
    }

    private fun scaledCopy(bitmap: Bitmap, maxSide: Int): Bitmap {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
        val copy = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        return if (copy === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else copy
    }

    private fun startLyricLookup(info: TrackInfo) {
        val requestId = ++lyricRequestId
        lyricCandidates = emptyList()
        candidatesSearched = false
        noLyricsChosen = false
        if (info.title.isBlank()) {
            lyricStatus = ""
            return
        }
        lyricStatus = "正在搜尋歌詞…"
        lyricExecutor.execute {
            if (requestId != lyricRequestId) return@execute // skipped past this song already
            val query = SongQuery(info.title, info.artist, info.album, info.durationMs, info.spotifyId)
            var result: LyricResult? = null
            var candidates: List<LyricCandidate>? = null
            // A lyric the user picked by hand for this song wins.
            val (noLyrics, chosen) = LyricChoices.get(query)
            try {
                if (chosen != null) LyricRepository.fetch(chosen)?.let { result = LyricResult(it, chosen) }
                // Then a matching file in the local lyrics folder.
                if (result == null && !noLyrics) result = LocalLyrics.find(query)
                if (result == null && !noLyrics) {
                    val found = LyricRepository.find(query)
                    result = found.first
                    candidates = found.second
                }
            } catch (_: Throwable) {
            }
            main.post {
                if (requestId != lyricRequestId || track?.key != info.key) return@post
                noLyricsChosen = noLyrics
                candidates?.let {
                    lyricCandidates = it
                    candidatesSearched = true
                }
                setLyrics(result)
            }
        }
    }

    private fun setLyrics(result: LyricResult?) {
        lyricResult = result
        lyricContainer = null
        lyricStatus = when {
            noLyricsChosen -> "已設定這首歌不顯示歌詞"
            result == null -> "找不到同步歌詞"
            else -> "歌詞：${result.sourceLabel}"
        }
        pushLyric()
        notifyUi()
    }

    // ---------- manual lyric choice ----------

    private fun currentQuery(): SongQuery? = track?.let { SongQuery(it.title, it.artist, it.album, it.durationMs) }

    /** Candidates for the picker; searches now if the song was matched from a remembered choice. */
    fun loadCandidates(callback: (List<LyricCandidate>) -> Unit) {
        val query = currentQuery() ?: return callback(emptyList())
        val key = track?.key
        if (candidatesSearched) return callback(lyricCandidates)
        lyricExecutor.execute {
            val found = runCatching { LyricRepository.searchAll(query) }.getOrDefault(emptyList())
            main.post {
                if (track?.key == key) {
                    lyricCandidates = found
                    candidatesSearched = true
                }
                callback(found)
            }
        }
    }

    /** Use this candidate for the current song and remember it. Calls back with success. */
    fun chooseCandidate(candidate: LyricCandidate, callback: (Boolean) -> Unit) {
        val query = currentQuery() ?: return callback(false)
        val key = track?.key
        lyricExecutor.execute {
            val doc = runCatching { LyricRepository.fetch(candidate) }.getOrNull()
            main.post {
                if (doc == null || track?.key != key) return@post callback(false)
                LyricChoices.set(query, candidate)
                lyricRequestId++
                noLyricsChosen = false
                setLyrics(LyricResult(doc, candidate))
                callback(true)
            }
        }
    }

    /** Show no lyrics for this song (remembered). */
    fun chooseNone() {
        val query = currentQuery() ?: return
        LyricChoices.setNone(query)
        lyricRequestId++
        noLyricsChosen = true
        setLyrics(null)
    }

    /** Search the current song again (e.g. after the local lyrics folder changed). */
    fun refreshLyrics() {
        val query = currentQuery() ?: return
        val info = track ?: return
        LyricRepository.forget(query)
        startLyricLookup(info)
        notifyUi()
    }

    /** Forget the hand-picked lyric for this song and search again. */
    fun resetChoice() {
        val query = currentQuery() ?: return
        val info = track ?: return
        LyricChoices.remove(query)
        LyricRepository.forget(query)
        startLyricLookup(info)
        notifyUi()
    }

    // ---------- playback state ----------

    fun isPlaying(): Boolean = if (demo) demoPlaying else current?.playbackState?.state == PlaybackState.STATE_PLAYING

    /** The player's position right now, extrapolated from its last report. */
    fun positionMs(): Long {
        if (demo) return demoPositionMs()
        val state = current?.playbackState ?: return 0L
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        val duration = track?.durationMs ?: 0L
        if (duration > 0) position = position.coerceAtMost(duration)
        return position.coerceAtLeast(0L)
    }

    // Progress first: Folia's pause handler reuses the latest precise progress, so it must be fresh.
    private fun onPlayback() {
        pushProgress()
        pushPauseState()
        notifyUi()
    }

    // ---------- controls ----------

    fun togglePlayPause() {
        if (demo) {
            demoBaseMs = demoPositionMs()
            demoSince = SystemClock.elapsedRealtime()
            demoPlaying = !demoPlaying
            onPlayback()
            return
        }
        val controller = current ?: return
        if (isPlaying()) controller.transportControls.pause() else controller.transportControls.play()
    }

    fun skipNext() {
        current?.transportControls?.skipToNext()
    }

    fun skipPrevious() {
        current?.transportControls?.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        current?.transportControls?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun sourceAppLabel(): String? {
        if (demo) return "示範"
        val pkg = current?.packageName ?: return null
        return try {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    fun sourceAppLaunchIntent(): Intent? {
        val pkg = current?.packageName ?: return null
        return appContext.packageManager.getLaunchIntentForPackage(pkg)
    }

    // ---------- preview without a player (CI screenshots) ----------

    var demo = false
        private set
    private var demoPlaying = true
    private var demoBaseMs = 0L
    private var demoSince = 0L

    private fun demoPositionMs(): Long {
        val elapsed = if (demoPlaying) SystemClock.elapsedRealtime() - demoSince else 0L
        return (demoBaseMs + elapsed) % DemoSong.DURATION_MS
    }

    /** Shows [DemoSong] with [cover] as if a player were playing it. */
    fun startDemo(cover: Bitmap) {
        stop()
        demo = true
        demoPlaying = true
        demoBaseMs = DemoSong.START_MS
        demoSince = SystemClock.elapsedRealtime()
        lyricRequestId++
        track = TrackInfo("demo", null, "示範歌曲", "流詞", "介面預覽", DemoSong.DURATION_MS, "demo")
        coverSource = cover
        encodeCover(cover, "demo")
        noLyricsChosen = false
        setLyrics(LyricResult(DemoSong.doc(), LyricCandidate("local", "demo", "示範歌曲", "流詞", "", DemoSong.DURATION_MS)))
        pushSnapshot()
    }

    // ---------- events for Folia ----------

    fun pushSnapshot() {
        pushTrack()
        pushLyric()
        pushProgress()
        pushPauseState()
    }

    private fun push(event: String, data: JSONObject) {
        val sink = webSink ?: return
        sink(JSONObject().put("event", event).put("data", data).toString())
    }

    private fun pushTrack() {
        val info = track
        val data = JSONObject()
        if (info != null) {
            data.put("id", info.key.hashCode().toString())
            data.put("title", info.title.ifBlank { "Now Playing" })
            data.put("author", info.artist)
            data.put("album", info.album)
            if (info.durationMs > 0) data.put("duration", info.durationMs)
            coverDataUrl?.let { data.put("cover", it) }
        }
        push("Track", data)
    }

    private fun pushLyric() {
        val info = track
        val result = lyricResult
        val data = JSONObject()
        if (info == null || result == null) {
            data.put("hasLyric", false)
            push("Lyric", data)
            return
        }
        // Everything goes to Folia as one lyric container (word timing + translation + romaji);
        // which subtitle line shows is each screen's own setting. Simplified → Traditional applies to
        // every source; Japanese originals (kana) and romaji are left alone.
        val container = lyricContainer ?: (result.doc.rawLrc?.let { raw ->
            // A local .lrc goes to Folia untouched (its parser knows every LRC dialect), just converted.
            if (convertToTraditional && !LyricFormats.hasKana(result.doc)) LyricRepository.toTraditional(raw) else raw
        } ?: LyricFormats.toFoliaContainer(
            if (convertToTraditional) LyricFormats.convertChinese(result.doc, LyricRepository::toTraditional) else result.doc,
        )).also { lyricContainer = it }

        data.put("source", result.candidate.source)
        data.put("title", info.title)
        data.put("author", info.artist)
        if (info.durationMs > 0) data.put("duration", info.durationMs)
        data.put("hasLyric", true)
        data.put("lrc", container)
        push("Lyric", data)
    }

    private fun lyricClockMs(): Long = (positionMs() - lyricOffsetMs).coerceAtLeast(0L)

    private fun pushPauseState() {
        val data = JSONObject()
            .put("isPaused", !isPlaying())
            .put("seekbarCurrentPosition", lyricClockMs() / 1000.0)
        push("PlayerPauseState", data)
    }

    private fun pushProgress() {
        val clock = lyricClockMs()
        lastSentClockMs = clock
        lastSentAtMs = SystemClock.elapsedRealtime()
        lastSentPlaying = isPlaying()
        push("PlayerProgress", JSONObject().put("progress", clock))
    }

    private fun notifyUi() {
        uiListener?.invoke()
    }
}
