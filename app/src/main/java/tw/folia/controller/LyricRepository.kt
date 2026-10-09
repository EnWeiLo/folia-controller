package tw.folia.controller

import android.icu.text.Transliterator
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

/** What the player says is playing. */
data class SongQuery(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** Spotify's track id when Spotify is the player (it publishes `spotify:track:<id>`). */
    val spotifyId: String? = null,
)

/** A song found on one lyric source. [extra] holds what that source needs to fetch it again. */
data class LyricCandidate(
    val source: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val extra: Map<String, String> = emptyMap(),
    val score: Int = 0,
    val accepted: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("source", source).put("id", id).put("title", title).put("artist", artist).put("album", album)
        .put("durationMs", durationMs).put("score", score).put("accepted", accepted)
        .put("extra", JSONObject(extra as Map<*, *>))

    companion object {
        fun fromJson(o: JSONObject): LyricCandidate {
            val extra = HashMap<String, String>()
            o.optJSONObject("extra")?.let { e -> e.keys().forEach { k -> extra[k] = e.optString(k) } }
            return LyricCandidate(
                o.optString("source"), o.optString("id"), o.optString("title"), o.optString("artist"),
                o.optString("album"), o.optLong("durationMs"), extra, o.optInt("score"), o.optBoolean("accepted"),
            )
        }
    }
}

/** Lyrics ready for Folia, plus where they came from. */
data class LyricResult(val doc: LyricDoc, val candidate: LyricCandidate) {
    val sourceLabel: String
        get() = LyricRepository.sourceName(candidate.source) + if (doc.wordByWord) "（逐字）" else ""
}

/**
 * Finds synced lyrics for whatever another app is playing. Same rules as the Windows app
 * (LyricRepository.cs):
 *  - NetEase, QQ Music, Kugou and LRCLIB are searched at the same time and every hit is scored on
 *    title, artist and length; a romanised title ("Yoru ni Kakeru") still matches on artist + exact length;
 *  - the AMLL database (hand-made word-by-word lyrics) is tried first for the best NetEase / QQ hit;
 *  - word-by-word lyrics win over line-by-line ones; Chinese sources come first for CJK titles.
 * Everything here blocks: call it off the main thread.
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
    private val QQ_HEADERS = mapOf(
        "Cookie" to "tmeLoginType=-1;",
        "Content-Type" to "application/json",
        "User-Agent" to "okhttp/3.14.9",
    )
    private const val KUGOU_SECRET = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"

    private val BRACKETS = Regex("""\s*[(\[（【「][^)\]）】」]*[)\]）】」]""")
    private val DASH_SUFFIX = Regex("""\s+[-–—]\s+.*$""")
    // Players join several artists with ", " or "、"; "&", "/" and "x" are often part of one name.
    private val ARTIST_SPLIT = Regex("""\s*(?:,|，|、|;|\s+feat\.?\s+|\s+ft\.?\s+)\s*""", RegexOption.IGNORE_CASE)
    private val KANA = Regex("""[぀-ヿ]""")
    private val CJK = Regex("""[぀-ヿ㐀-鿿가-힯]""")
    private val PURE_MUSIC = Regex("""纯音乐|純音樂|此歌曲为没有填词的纯音乐""")
    // Other versions of a song; matched on lower-cased titles (Latin words must stand alone).
    private val VERSION_LATIN = Regex("""(?<![a-z])(live|remix|mix|acoustic|instrumental|inst|karaoke|off vocal|cover|demo|ver|version|sped up|slowed)(?![a-z])""")
    private val VERSION_CJK = Regex("""伴奏|纯音乐|純音樂|现场|現場|翻唱|カラオケ|ライブ""")

    private val pool = Executors.newFixedThreadPool(6)

    private val cache = object : LinkedHashMap<String, Pair<LyricResult?, List<LyricCandidate>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<LyricResult?, List<LyricCandidate>>>?) = size > 60
    }

    fun sourceName(source: String): String = when (source) {
        "netease" -> "網易雲"
        "qq" -> "QQ 音樂"
        "kugou" -> "酷狗"
        "lrclib" -> "LRCLIB"
        "amll-ncm", "amll-qq", "amll-spotify" -> "AMLL"
        "local" -> "本機檔案"
        else -> source
    }

    // ---------- automatic search ----------

    /** Best lyrics and every candidate seen (for the manual picker). */
    fun find(query: SongQuery): Pair<LyricResult?, List<LyricCandidate>> {
        if (query.title.isBlank()) return null to emptyList()
        val key = "${query.title}|${query.artist}|${query.durationMs / 1000}"
        synchronized(cache) { cache[key]?.let { return it } }

        // Spotify tells us exactly which track it is: the AMLL database is indexed by that id.
        val spotify = query.spotifyId?.let { id ->
            val c = LyricCandidate("amll-spotify", id, query.title, query.artist, query.album, query.durationMs, emptyMap(), 20, true)
            runCatching { fetch(c) }.getOrNull()?.let { LyricResult(it, c) }
        }
        val candidates = searchAll(query)
        val accepted = candidates.filter { it.accepted }
        val result = spotify ?: pickBest(query, accepted)
        val outcome = result to candidates
        if (result != null || candidates.isNotEmpty()) synchronized(cache) { cache[key] = outcome }
        return outcome
    }

    /** Drops the cached search for this song (after the user resets a hand-picked lyric). */
    fun forget(query: SongQuery) {
        synchronized(cache) { cache.remove("${query.title}|${query.artist}|${query.durationMs / 1000}") }
    }

    /** Every source, in parallel; scored and sorted best first. */
    fun searchAll(query: SongQuery): List<LyricCandidate> {
        val tasks = listOf(
            Callable { searchNetease(query) },
            Callable { searchQq(query) },
            Callable { searchKugou(query) },
            Callable { searchLrclib(query) },
        ).map { pool.submit(it) }
        val all = tasks.flatMap { future -> runCatching { future.get(15, TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
        val order = sourceOrder(query)
        // Matching files in the local lyrics folder always come first.
        return runCatching { LocalLyrics.candidates(query) }.getOrDefault(emptyList()) + all.sortedWith(compareByDescending<LyricCandidate> { it.accepted }.thenByDescending { it.score }
            .thenBy { order.indexOf(it.source) })
    }

    private fun sourceOrder(query: SongQuery): List<String> =
        if (CJK.containsMatchIn(query.title + query.artist)) listOf("netease", "qq", "kugou", "lrclib")
        else listOf("lrclib", "netease", "qq", "kugou")

    private fun pickBest(query: SongQuery, accepted: List<LyricCandidate>): LyricResult? {
        if (accepted.isEmpty()) return null
        // 1. AMLL: hand-made word-by-word lyrics, keyed by NetEase / QQ song id.
        val amllProbes = listOfNotNull(
            accepted.firstOrNull { it.source == "netease" }?.let { it.copy(source = "amll-ncm", extra = it.extra + ("from" to "netease")) },
            accepted.firstOrNull { it.source == "qq" }?.let { it.copy(source = "amll-qq", extra = it.extra + ("from" to "qq")) },
        ).map { c -> c to pool.submit(Callable { fetch(c) }) }
        for ((candidate, future) in amllProbes) {
            val doc = runCatching { future.get(8, TimeUnit.SECONDS) }.getOrNull()
            if (doc != null && !doc.isEmpty) return LyricResult(doc, candidate)
        }
        // 2. The scored candidates in order; prefer word-by-word over line-by-line.
        var fallback: LyricResult? = null
        var tried = 0
        for (candidate in accepted) {
            if (tried >= 6) break
            if (fallback != null && candidate.source == "lrclib") continue // line-by-line only
            tried++
            val doc = runCatching { fetch(candidate) }.getOrNull() ?: continue
            if (doc.isEmpty) continue
            val result = LyricResult(doc, candidate)
            if (doc.wordByWord) return result
            if (fallback == null) fallback = result
        }
        return fallback
    }

    /** Lyrics for one candidate (also used when the user picks one by hand). */
    fun fetch(c: LyricCandidate): LyricDoc? = when (c.source) {
        "netease" -> fetchNetease(c.id)
        "qq" -> fetchQq(c)
        "kugou" -> fetchKugou(c)
        "lrclib" -> c.extra["synced"]?.let { LyricFormats.parseLrc(it) } ?: fetchLrclib(c.id)
        "amll-ncm" -> fetchAmll("ncm", c.id)
        "amll-qq" -> fetchAmll("qq", c.id)
        "amll-spotify" -> fetchAmll("spotify", c.id)
        "local" -> LocalLyrics.load(c.id)
        else -> null
    }?.takeUnless { doc -> doc.isEmpty || doc.lines.all { PURE_MUSIC.containsMatchIn(it.text) } }

    // ---------- scoring ----------

    /** Title, artist and length agreement → score; null when it is clearly another song. */
    private fun score(query: SongQuery, title: String, artists: List<String>, durationMs: Long): Pair<Int, Boolean>? {
        val wantTitle = norm(cleanTitle(query.title))
        val gotTitle = norm(cleanTitle(title))
        val wantArtist = norm(primaryArtist(query.artist))
        val titleScore = when {
            wantTitle.isEmpty() || gotTitle.isEmpty() -> 0
            gotTitle == wantTitle -> 4
            (gotTitle.contains(wantTitle) || wantTitle.contains(gotTitle)) && min(gotTitle.length, wantTitle.length) >= 2 -> 2
            else -> 0
        }
        val artistMatch = wantArtist.isNotEmpty() && artists.any {
            val a = norm(it)
            a.isNotEmpty() && (a == wantArtist || a.contains(wantArtist) || wantArtist.contains(a))
        }
        val diff = if (query.durationMs > 0 && durationMs > 0) abs(durationMs - query.durationMs) else null
        if (diff != null && diff > 15_000) return null
        var score = titleScore + (if (artistMatch) 3 else 0)
        // Same title before cleaning: the studio track beats "Title (Live …)".
        if (titleScore == 4 && norm(title) == norm(query.title)) score += 1
        // A live / remix / karaoke … version the player is not playing.
        if ((versionTags(title) - versionTags(query.title)).isNotEmpty()) score -= 2
        if (diff != null) score += when {
            diff <= 2500 -> 3
            diff <= 5000 -> 1
            diff > 8000 -> -3
            else -> 0
        }
        // A romanised or translated title cannot match by text: same artist + same length will do.
        val accepted = if (titleScore == 0) artistMatch && diff != null && diff <= 1500 else score >= 6
        return score to accepted
    }

    private fun candidate(
        query: SongQuery, source: String, id: String, title: String, artists: List<String>, album: String,
        durationMs: Long, extra: Map<String, String> = emptyMap(),
    ): LyricCandidate? {
        val (score, accepted) = score(query, title, artists, durationMs) ?: return null
        return LyricCandidate(source, id, title, artists.joinToString(" / "), album, durationMs, extra, score, accepted)
    }

    private fun versionTags(title: String): Set<String> {
        val lower = title.lowercase()
        return (VERSION_LATIN.findAll(lower).map { it.value } + VERSION_CJK.findAll(lower).map { it.value }).toSet()
    }

    /**
     * Queries to try, in order: the text as the player shows it; the Simplified spelling (Chinese
     * catalogs) when that differs — never first, since Japanese names in kanji only (米津玄師) must
     * stay as they are; then, for Latin titles, the artist alone (romanised titles).
     */
    private fun searchTerms(query: SongQuery, format: (String, String) -> String = { t, a -> "$t $a".trim() }): List<String> {
        val title = cleanTitle(query.title)
        val artist = primaryArtist(query.artist)
        val terms = LinkedHashSet<String>()
        val original = format(title, artist)
        terms.add(original)
        if (!KANA.containsMatchIn(original)) terms.add(toSimplified(original))
        if (artist.isNotEmpty() && !CJK.containsMatchIn(title)) terms.add(artist)
        return terms.toList()
    }

    private fun searchUntilAccepted(
        query: SongQuery,
        terms: List<String> = searchTerms(query),
        search: (String) -> List<LyricCandidate>,
    ): List<LyricCandidate> {
        val all = ArrayList<LyricCandidate>()
        for (term in terms) {
            val found = runCatching { search(term) }.getOrDefault(emptyList())
            all.addAll(found.filter { c -> all.none { it.id == c.id } })
            if (found.any { it.accepted }) break
        }
        return all
    }

    // ---------- NetEase ----------

    private fun searchNetease(query: SongQuery): List<LyricCandidate> = searchUntilAccepted(query) { term ->
        val body = httpGet("https://music.163.com/api/search/get/web?s=${enc(term)}&type=1&offset=0&limit=20", NETEASE_HEADERS)
            ?: return@searchUntilAccepted emptyList()
        val songs = obj(body)?.optJSONObject("result")?.optJSONArray("songs") ?: return@searchUntilAccepted emptyList()
        (0 until songs.length()).mapNotNull { i ->
            val s = songs.optJSONObject(i) ?: return@mapNotNull null
            val artists = s.optJSONArray("artists").names("name")
            candidate(query, "netease", s.optLong("id").toString(), s.optString("name"), artists,
                s.optJSONObject("album")?.optString("name").orEmpty(), s.optLong("duration"))
        }
    }

    private fun fetchNetease(id: String): LyricDoc? {
        val body = httpGet("https://music.163.com/api/song/lyric/v1?id=$id&cp=false&lv=0&kv=0&tv=0&rv=0&yv=0&ytv=0&yrv=0", NETEASE_HEADERS)
            ?: httpGet("https://music.163.com/api/song/lyric?id=$id&lv=-1&kv=-1&tv=-1&rv=-1", NETEASE_HEADERS)
            ?: return null
        val o = obj(body) ?: return null
        fun track(name: String) = o.optJSONObject(name)?.optString("lyric")?.takeIf { it.isNotBlank() }
        val yrc = track("yrc")
        val lrc = track("lrc")
        return if (yrc != null) {
            LyricFormats.parseYrc(yrc).copy(
                translation = LyricFormats.parseTrack(track("ytlrc") ?: track("tlyric")),
                romaji = LyricFormats.parseTrack(track("yromalrc") ?: track("romalrc")),
            )
        } else if (lrc != null) {
            LyricFormats.parseLrc(lrc).copy(
                translation = LyricFormats.parseTrack(track("tlyric")),
                romaji = LyricFormats.parseTrack(track("romalrc")),
            )
        } else null
    }

    // ---------- QQ Music ----------

    private fun qqRequest(method: String, module: String, param: JSONObject): JSONObject? {
        val comm = JSONObject().put("ct", 11).put("cv", "1003006").put("v", "1003006").put("os_ver", "15")
            .put("phonetype", "24122RKC7C").put("tmeAppID", "qqmusiclight").put("nettype", "NETWORK_WIFI")
            .put("udid", "0").put("uid", "0")
            .put("rom", "Redmi/miro/miro:15/AE3A.240806.005/OS2.0.102.0.VOMCNXM:user/release-keys")
        val payload = JSONObject().put("comm", comm)
            .put("request", JSONObject().put("method", method).put("module", module).put("param", param))
        val body = httpPost("https://u.y.qq.com/cgi-bin/musicu.fcg", payload.toString(), QQ_HEADERS) ?: return null
        val o = obj(body) ?: return null
        if (o.optInt("code", -1) != 0) return null
        val request = o.optJSONObject("request") ?: return null
        return if (request.optInt("code", -1) == 0) request.optJSONObject("data") else null
    }

    private fun searchQq(query: SongQuery): List<LyricCandidate> = searchUntilAccepted(query) { term ->
        val param = JSONObject()
            .put("search_id", ((Math.random() * 1e14).toLong() + System.currentTimeMillis() % 86_400_000).toString())
            .put("remoteplace", "search.android.keyboard").put("query", term.take(60)).put("search_type", 0)
            .put("num_per_page", 20).put("page_num", 1).put("highlight", 0).put("nqc_flag", 0).put("page_id", 1).put("grp", 1)
        val songs = qqRequest("DoSearchForQQMusicLite", "music.search.SearchCgiService", param)
            ?.optJSONObject("body")?.optJSONArray("item_song") ?: return@searchUntilAccepted emptyList()
        (0 until songs.length()).mapNotNull { i ->
            val s = songs.optJSONObject(i) ?: return@mapNotNull null
            val id = s.optLong("id")
            if (id <= 0) return@mapNotNull null
            val artists = s.optJSONArray("singer").names("name")
            val album = s.optJSONObject("album")?.optString("name").orEmpty()
            candidate(query, "qq", id.toString(), s.optString("title"), artists, album, s.optLong("interval") * 1000,
                mapOf("mid" to s.optString("mid"), "singer" to artists.joinToString(", ")))
        }
    }

    private fun fetchQq(c: LyricCandidate): LyricDoc? {
        fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
        val param = JSONObject()
            .put("albumName", b64(c.album)).put("crypt", 1).put("ct", 19).put("cv", 2111)
            .put("interval", c.durationMs / 1000).put("lrc_t", 0).put("qrc", 1).put("qrc_t", 0)
            .put("roma", 1).put("roma_t", 0).put("singerName", b64(c.extra["singer"] ?: c.artist))
            .put("songID", c.id.toLong()).put("songName", b64(c.title)).put("trans", 1).put("trans_t", 0).put("type", 0)
        val data = qqRequest("GetPlayLyricInfo", "music.musichallSong.PlayLyricInfo", param) ?: return null
        fun decrypt(field: String): String? = data.optString(field).takeIf { it.length >= 16 }
            ?.let { runCatching { LyricFormats.decryptQrc(it) }.getOrNull() }?.takeIf { it.isNotBlank() }
        val lyric = decrypt("lyric") ?: return null
        val doc = if (LyricFormats.isQrc(lyric)) LyricFormats.parseQrc(lyric) else LyricFormats.parseLrc(lyric)
        val romaji = decrypt("roma")?.let { r ->
            if (LyricFormats.isQrc(r)) LyricFormats.parseQrc(r).lines.map { TimedText(it.startMs, it.text) } else LyricFormats.parseTrack(r)
        } ?: emptyList()
        return doc.copy(translation = LyricFormats.parseTrack(decrypt("trans")), romaji = romaji)
    }

    // ---------- Kugou ----------

    private fun kugouSigned(url: String, params: Map<String, String>): JSONObject? {
        val all = sortedMapOf("appid" to "3116", "clientver" to "11070")
        all.putAll(params)
        val signText = KUGOU_SECRET + all.entries.joinToString("") { "${it.key}=${it.value}" } + KUGOU_SECRET
        val signature = MessageDigest.getInstance("MD5").digest(signText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val query = all.entries.joinToString("&") { "${it.key}=${enc(it.value)}" } + "&signature=$signature"
        val body = httpGet("$url?$query", mapOf(
            "User-Agent" to "Android14-1070-11070-201-0-Lyric-wifi",
            "KG-Rec" to "1", "KG-RC" to "1", "KG-CLIENTTIMEMS" to System.currentTimeMillis().toString(),
        )) ?: return null
        val o = obj(body) ?: return null
        val error = o.optInt("error_code", 0)
        return if (error == 0 || error == 200) o else null
    }

    private fun searchKugou(query: SongQuery): List<LyricCandidate> {
        val title = cleanTitle(query.title)
        val artist = primaryArtist(query.artist)
        val terms = searchTerms(query) { t, a -> if (a.isNotEmpty()) "$a - $t" else t }.filter { it.contains(title) || it.contains(toSimplified(title)) }
        return searchUntilAccepted(query, terms) { keyword -> searchKugouOnce(query, keyword, title, artist) }
    }

    private fun searchKugouOnce(query: SongQuery, keyword: String, title: String, artist: String): List<LyricCandidate> {
        val o = kugouSigned("https://lyrics.kugou.com/v1/search", mapOf(
            "album_audio_id" to "", "duration" to query.durationMs.toString(), "hash" to "",
            "keyword" to keyword, "lrctxt" to "1", "man" to "no",
        )) ?: return emptyList()
        val list = o.optJSONArray("candidates") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val c = list.optJSONObject(i) ?: return@mapNotNull null
            val id = c.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val songName = c.optString("song").ifBlank { title }
            val singer = c.optString("singer").ifBlank { artist }
            candidate(query, "kugou", id, songName, listOf(singer), "", c.optLong("duration"),
                mapOf("accesskey" to c.optString("accesskey")))
        }
    }

    private fun fetchKugou(c: LyricCandidate): LyricDoc? {
        val o = kugouSigned("https://lyrics.kugou.com/download", mapOf(
            "accesskey" to (c.extra["accesskey"] ?: return null), "charset" to "utf8", "client" to "mobi",
            "fmt" to "krc", "id" to c.id, "ver" to "1",
        )) ?: return null
        val content = o.optString("content").takeIf { it.isNotBlank() } ?: return null
        if (o.optInt("contenttype") == 2) {
            return LyricFormats.parseLrc(String(Base64.getMimeDecoder().decode(content), Charsets.UTF_8))
        }
        val krc = runCatching { LyricFormats.decryptKrc(content) }.getOrNull() ?: return null
        val doc = LyricFormats.parseKrc(krc)
        val tag = LyricFormats.krcLanguageTag(krc) ?: return doc
        val language = runCatching { JSONObject(String(Base64.getMimeDecoder().decode(tag), Charsets.UTF_8)) }.getOrNull() ?: return doc
        val starts = LyricFormats.krcLineStarts(krc)
        val translation = ArrayList<TimedText>()
        val romaji = ArrayList<TimedText>()
        val contents = language.optJSONArray("content") ?: return doc
        for (i in 0 until contents.length()) {
            val item = contents.optJSONObject(i) ?: continue
            val rows = item.optJSONArray("lyricContent") ?: continue
            when (item.optInt("type", -1)) {
                1 -> for (r in 0 until min(rows.length(), starts.size)) {
                    // One entry per KRC line, blank lines included.
                    rows.optJSONArray(r)?.optString(0)?.takeIf { it.isNotBlank() }?.let { translation.add(TimedText(starts[r].first, it)) }
                }
                0 -> {
                    // One entry per non-blank KRC line, one string per word.
                    val sung = starts.filter { it.second }
                    for (r in 0 until min(rows.length(), sung.size)) {
                        val words = rows.optJSONArray(r) ?: continue
                        val text = (0 until words.length()).joinToString("") { words.optString(it) }.trim()
                        if (text.isNotEmpty()) romaji.add(TimedText(sung[r].first, text))
                    }
                }
            }
        }
        return doc.copy(translation = translation, romaji = romaji)
    }

    // ---------- LRCLIB ----------

    private fun searchLrclib(query: SongQuery): List<LyricCandidate> {
        val title = cleanTitle(query.title)
        val artist = primaryArtist(query.artist)
        val out = ArrayList<LyricCandidate>()
        fun collect(params: String) {
            val body = httpGet("https://lrclib.net/api/search?$params", mapOf("User-Agent" to APP_UA)) ?: return
            val items = arr(body) ?: return
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val synced = it.optString("syncedLyrics").takeIf { s -> s.isNotBlank() && s != "null" } ?: continue
                val id = it.optLong("id").toString()
                if (out.any { c -> c.id == id }) continue
                candidate(query, "lrclib", id, it.optString("trackName"), listOf(it.optString("artistName")),
                    it.optString("albumName"), (it.optDouble("duration", 0.0) * 1000).toLong(), mapOf("synced" to synced))
                    ?.let(out::add)
            }
        }
        collect("track_name=${enc(title)}" + if (artist.isNotEmpty()) "&artist_name=${enc(artist)}" else "")
        if (out.none { it.accepted }) collect("q=${enc("$title $artist".trim())}")
        return out
    }

    private fun fetchLrclib(id: String): LyricDoc? {
        val body = httpGet("https://lrclib.net/api/get/$id", mapOf("User-Agent" to APP_UA)) ?: return null
        val synced = obj(body)?.optString("syncedLyrics")?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return LyricFormats.parseLrc(synced)
    }

    // ---------- AMLL ----------

    private fun fetchAmll(platform: String, id: String): LyricDoc? {
        val body = httpGet("https://amll-ttml-db.stevexmh.net/$platform/${enc(id)}?format=ttml", mapOf("User-Agent" to APP_UA), timeoutMs = 6000)
            ?: return null
        if (!body.contains("<tt")) return null
        return LyricFormats.parseTtml(body)
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

    fun toTraditional(text: String): String {
        val t = toTraditionalT ?: return text
        return synchronized(t) { t.transliterate(text) }
    }

    // ---------- helpers ----------

    fun cleanTitle(title: String): String {
        val noBrackets = BRACKETS.replace(title, "")
        return DASH_SUFFIX.replace(noBrackets, "").trim().ifEmpty { title.trim() }
    }

    fun primaryArtist(artist: String): String =
        artist.split(ARTIST_SPLIT).firstOrNull { it.isNotBlank() }?.trim() ?: artist.trim()

    private fun norm(text: String): String = normalize(text)

    /** Letters and digits only, lower case, Simplified: for comparing names across sources. */
    fun normalize(text: String): String = toSimplified(text).lowercase().filter { it.isLetterOrDigit() }

    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun JSONArray?.names(key: String): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.optString(key)?.takeIf { n -> n.isNotBlank() } }

    private fun obj(body: String?): JSONObject? = body?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun arr(body: String?): JSONArray? = body?.let { runCatching { JSONArray(it) }.getOrNull() }

    private fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int = 10_000): String? = http(url, "GET", null, headers, timeoutMs)

    private fun httpPost(url: String, body: String, headers: Map<String, String>): String? = http(url, "POST", body, headers, 10_000)

    private fun http(url: String, method: String, body: String?, headers: Map<String, String>, timeoutMs: Int): String? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 6000
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        try {
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else null
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}
