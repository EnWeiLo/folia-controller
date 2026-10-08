package tw.folia.controller

import android.icu.text.Transliterator
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** Timed lyrics for one song. [karaoke] is NetEase YRC (word-by-word); [lrc] is line-timed LRC. */
data class LyricResult(
    val source: String,
    val sourceLabel: String,
    val lrc: String?,
    val karaoke: String?,
    val translation: String?,
)

/**
 * Finds synced lyrics for whatever another app is playing.
 * NetEase first for CJK titles (better coverage, word-by-word YRC), LRCLIB first otherwise.
 * Every call is blocking: run it off the main thread.
 */
object LyricRepository {
    private const val APP_UA = "FoliaController/1.0 (Android; personal lyrics display)"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"
    private val NETEASE_HEADERS = mapOf(
        "Referer" to "https://music.163.com/",
        "User-Agent" to BROWSER_UA,
        "Cookie" to "os=pc; appver=2.10.13",
    )
    private val TIMESTAMP = Regex("""\[\d{1,3}:\d{1,2}""")
    private val YRC_LINE = Regex("""^\[\d+,\d+]""", RegexOption.MULTILINE)
    private val BRACKETS = Regex("""\s*[(\[（【「][^)\]）】」]*[)\]）】」]""")
    private val DASH_SUFFIX = Regex("""\s+[-–—]\s+.*$""")
    // Players join several artists with ", " (Spotify) or "、"; "&", "/" and "x" are often part of
    // a single name (Simon & Garfunkel, AC/DC, Lil Nas X), so they are not split on.
    private val ARTIST_SPLIT = Regex("""\s*(?:,|，|、|;|\s+feat\.?\s+|\s+ft\.?\s+)\s*""", RegexOption.IGNORE_CASE)
    private val KANA = Regex("""[぀-ヿ]""")
    private val CJK = Regex("""[぀-ヿ㐀-鿿가-힯]""")

    private val cache = object : LinkedHashMap<String, LyricResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricResult>?) = size > 80
    }

    fun find(title: String, artist: String, album: String, durationMs: Long): LyricResult? {
        if (title.isBlank()) return null
        val key = "$title|$artist|${durationMs / 1000}"
        synchronized(cache) { cache[key]?.let { return it } }

        val preferNetease = CJK.containsMatchIn(title + artist)
        val result = if (preferNetease) {
            netease(title, artist, durationMs) ?: lrclib(title, artist, album, durationMs)
        } else {
            lrclib(title, artist, album, durationMs) ?: netease(title, artist, durationMs)
        }
        if (result != null) synchronized(cache) { cache[key] = result }
        return result
    }

    // ---------- LRCLIB ----------

    private fun lrclib(title: String, artist: String, album: String, durationMs: Long): LyricResult? {
        val mainArtist = primaryArtist(artist)
        for (t in titleVariants(title)) {
            if (durationMs > 0) {
                val query = buildString {
                    append("track_name=").append(enc(t))
                    append("&artist_name=").append(enc(mainArtist))
                    if (album.isNotBlank()) append("&album_name=").append(enc(album))
                    append("&duration=").append(durationMs / 1000)
                }
                val got = httpGet("https://lrclib.net/api/get?$query", mapOf("User-Agent" to APP_UA))
                if (got != null && got.first == 200) {
                    val synced = parseObject(got.second)?.str("syncedLyrics")
                    if (synced != null && TIMESTAMP.containsMatchIn(synced)) {
                        return LyricResult("lrclib", "LRCLIB", synced, null, null)
                    }
                }
            }

            lrclibSearch("track_name=${enc(t)}&artist_name=${enc(mainArtist)}", durationMs, 4000)
                ?.let { return LyricResult("lrclib", "LRCLIB", it, null, null) }
            // Artist spelled differently (e.g. 周杰倫 / Jay Chou): title only, but the length must match closely.
            if (durationMs > 0) {
                lrclibSearch("track_name=${enc(t)}", durationMs, 2000)
                    ?.let { return LyricResult("lrclib", "LRCLIB", it, null, null) }
            }
        }
        return null
    }

    private fun lrclibSearch(query: String, durationMs: Long, maxDiffMs: Long): String? {
        val search = httpGet("https://lrclib.net/api/search?$query", mapOf("User-Agent" to APP_UA)) ?: return null
        if (search.first != 200) return null
        val items = parseArray(search.second) ?: return null
        var best: String? = null
        var bestDiff = Long.MAX_VALUE
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val synced = item.str("syncedLyrics") ?: continue
            if (!TIMESTAMP.containsMatchIn(synced)) continue
            val diff = if (durationMs > 0) abs((item.optDouble("duration", 0.0) * 1000).toLong() - durationMs) else 0L
            if (durationMs > 0 && diff > maxDiffMs) continue
            if (diff < bestDiff) {
                bestDiff = diff
                best = synced
            }
        }
        return best
    }

    // ---------- NetEase Cloud Music ----------

    private fun netease(title: String, artist: String, durationMs: Long): LyricResult? {
        val cleanedTitle = cleanTitle(title)
        val mainArtist = primaryArtist(artist)
        val wantTitle = norm(cleanedTitle)
        val wantArtist = norm(mainArtist)
        if (wantTitle.isEmpty()) return null

        val query = toSimplified("$cleanedTitle $mainArtist".trim())
        val search = httpGet(
            "https://music.163.com/api/search/get/web?s=${enc(query)}&type=1&offset=0&limit=20",
            NETEASE_HEADERS,
        ) ?: return null
        val songs = parseObject(search.second)?.optJSONObject("result")?.optJSONArray("songs") ?: return null

        var bestId = -1L
        var bestScore = 0
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val name = norm(cleanTitle(song.optString("name")))
            if (name.isEmpty()) continue
            var score = when {
                name == wantTitle -> 4
                (name.contains(wantTitle) || wantTitle.contains(name)) && minOf(name.length, wantTitle.length) >= 2 -> 2
                else -> continue
            }
            val artists = song.optJSONArray("artists")
            if (wantArtist.isNotEmpty() && artists != null) {
                for (j in 0 until artists.length()) {
                    val a = norm(artists.optJSONObject(j)?.optString("name").orEmpty())
                    if (a.isNotEmpty() && (a == wantArtist || a.contains(wantArtist) || wantArtist.contains(a))) {
                        score += 3
                        break
                    }
                }
            }
            val songDuration = song.optLong("duration", 0L)
            if (durationMs > 0 && songDuration > 0) {
                val diff = abs(songDuration - durationMs)
                if (diff <= 2500) score += 3 else if (diff > 8000) score -= 3
            }
            if (score > bestScore) {
                bestScore = score
                bestId = song.optLong("id", -1L)
            }
        }
        // Title alone is not enough: also need the artist or the length to agree.
        if (bestId <= 0 || bestScore < 6) return null

        val lyric = neteaseLyric(bestId) ?: return null
        val yrc = lyric.optJSONObject("yrc")?.str("lyric")?.takeIf { YRC_LINE.containsMatchIn(it) }
        val lrc = lyric.optJSONObject("lrc")?.str("lyric")?.takeIf { TIMESTAMP.containsMatchIn(it) }
        val translation = lyric.optJSONObject("tlyric")?.str("lyric")?.takeIf { TIMESTAMP.containsMatchIn(it) }
        if (yrc == null && lrc == null) return null
        return LyricResult("netease", if (yrc != null) "網易雲（逐字）" else "網易雲", lrc, yrc, translation)
    }

    private fun neteaseLyric(id: Long): JSONObject? {
        val v1 = httpGet(
            "https://music.163.com/api/song/lyric/v1?id=$id&cp=false&lv=0&kv=0&tv=0&rv=0&yv=0&ytv=0&yrv=0",
            NETEASE_HEADERS,
        )
        parseObject(v1?.second)?.let { if (it.has("lrc") || it.has("yrc")) return it }
        val legacy = httpGet("https://music.163.com/api/song/lyric?id=$id&lv=-1&kv=-1&tv=-1", NETEASE_HEADERS)
        return parseObject(legacy?.second)?.takeIf { it.has("lrc") }
    }

    // ---------- Chinese script conversion ----------

    private val toSimplifiedT: Transliterator? by lazy { transliterator("Traditional-Simplified") }
    private val toTraditionalT: Transliterator? by lazy { transliterator("Simplified-Traditional") }

    private fun transliterator(id: String): Transliterator? =
        if (Build.VERSION.SDK_INT >= 29) runCatching { Transliterator.getInstance(id) }.getOrNull() else null

    fun toSimplified(text: String): String {
        val t = toSimplifiedT ?: return text
        return synchronized(t) { t.transliterate(text) }
    }

    /** Converts Simplified Chinese lyrics to Traditional; leaves Japanese (has kana) alone. */
    fun toTraditional(text: String): String {
        if (KANA.containsMatchIn(text)) return text
        val t = toTraditionalT ?: return text
        return synchronized(t) { t.transliterate(text) }
    }

    // ---------- helpers ----------

    private fun titleVariants(title: String): List<String> {
        val cleaned = cleanTitle(title)
        return if (cleaned.isNotBlank() && cleaned != title.trim()) listOf(title.trim(), cleaned) else listOf(title.trim())
    }

    private fun cleanTitle(title: String): String {
        val noBrackets = BRACKETS.replace(title, "")
        return DASH_SUFFIX.replace(noBrackets, "").trim().ifEmpty { title.trim() }
    }

    private fun primaryArtist(artist: String): String =
        artist.split(ARTIST_SPLIT).firstOrNull { it.isNotBlank() }?.trim() ?: artist.trim()

    private fun norm(text: String): String =
        toSimplified(text).lowercase().filter { it.isLetterOrDigit() }

    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun parseObject(body: String?): JSONObject? =
        body?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun parseArray(body: String?): JSONArray? =
        body?.let { runCatching { JSONArray(it) }.getOrNull() }

    private fun httpGet(url: String, headers: Map<String, String>): Pair<Int, String>? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            code to body
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}
