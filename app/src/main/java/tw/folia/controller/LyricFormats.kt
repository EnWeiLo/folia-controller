package tw.folia.controller

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Pure Kotlin (no Android APIs) so it can be tested on a plain JVM. The Windows app has a
// line-for-line twin in LyricFormats.cs; both must turn the fixtures in /tests/lyrics into the
// same bytes.

/** One timed word. Times are absolute milliseconds. */
data class LyricWord(val startMs: Long, val durationMs: Long, val text: String)

/** One lyric line. [words] is empty for line-timed lyrics. */
data class LyricLine(val startMs: Long, val words: List<LyricWord>, val text: String)

data class TimedText(val startMs: Long, val text: String)

/** Lyrics from any source, normalised: original lines plus optional translation / romaji tracks. */
data class LyricDoc(
    val lines: List<LyricLine>,
    val translation: List<TimedText> = emptyList(),
    val romaji: List<TimedText> = emptyList(),
    /** A local .lrc file's own text: sent to Folia as is, so its own parser handles every LRC dialect. */
    val rawLrc: String? = null,
) {
    val wordByWord: Boolean get() = lines.isNotEmpty() && lines.count { it.words.isNotEmpty() } * 2 >= lines.size
    val isEmpty: Boolean get() = lines.none { it.text.isNotBlank() }
}

object LyricFormats {
    private val LRC_TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val LRC_TAG_LINE = Regex("""^\[[a-zA-Z#]+:.*]$""")
    private val YRC_LINE = Regex("""^\[(\d+),(\d+)](.*)$""")
    // Word text runs up to the next timing marker, so lyrics that contain "(" or "<" survive.
    private val YRC_WORD = Regex("""\((\d+),(\d+),-?\d+\)((?:(?!\(\d+,\d+,-?\d+\)).)*)""")
    private val QRC_WORD = Regex("""((?:(?!\(\d+,\d+\)).)*)\((\d+),(\d+)\)""")
    private val KRC_WORD = Regex("""<(\d+),(\d+),-?\d+>((?:(?!<\d+,\d+,-?\d+>).)*)""")
    private val JSON_TEXT = Regex(""""tx"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val JSON_TIME = Regex(""""t"\s*:\s*(\d+)""")
    private val QRC_CONTENT = Regex("""LyricContent="([\s\S]*?)"\s*/>""")
    private val ENHANCED_WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val KANA = Regex("""[぀-ヿ]""")

    // ---------- parsers ----------

    /** Standard LRC (also NetEase's JSON credit lines). Several timestamps per line are allowed. */
    fun parseLrc(content: String): LyricDoc {
        val lines = ArrayList<LyricLine>()
        for (raw in content.replace("\r", "").split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("{")) {
                creditLine(line)?.let { lines.add(it) }
                continue
            }
            if (LRC_TAG_LINE.matches(line) && !LRC_TIME.containsMatchIn(line)) continue
            var rest = line
            val times = ArrayList<Long>()
            while (true) {
                val m = LRC_TIME.matchAt(rest, 0) ?: break
                times.add(lrcTimeMs(m))
                rest = rest.substring(m.range.last + 1)
            }
            if (times.isEmpty()) continue
            val text = ENHANCED_WORD_TAG.replace(rest, "").trim()
            // Enhanced LRC (`<mm:ss.xx>word<mm:ss.xx>word…`, e.g. from LDDC): word timing.
            val words = if (times.size == 1) enhancedWords(rest) else emptyList()
            for (t in times) lines.add(LyricLine(t, words, text))
        }
        lines.sortBy { it.startMs }
        return LyricDoc(lines)
    }

    /** Word timing from an enhanced-LRC line body; a word lasts until the next tag (500 ms for the last). */
    private fun enhancedWords(body: String): List<LyricWord> {
        val tags = ENHANCED_WORD_TAG.findAll(body).toList()
        if (tags.size < 2) return emptyList()
        val words = ArrayList<LyricWord>()
        for ((i, tag) in tags.withIndex()) {
            val end = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
            val text = body.substring(tag.range.last + 1, end)
            if (text.isEmpty()) continue
            val start = lrcTimeMs(tag)
            val next = if (i + 1 < tags.size) lrcTimeMs(tags[i + 1]) else start + 500
            words.add(LyricWord(start, max(1, next - start), text))
        }
        return trimLastWord(words).filter { it.text.isNotEmpty() }
    }

    /** LRC as a timed-text track (translation / romaji). Blank lines are dropped. */
    fun parseTrack(content: String?): List<TimedText> {
        if (content.isNullOrBlank()) return emptyList()
        return parseLrc(content).lines.filter { it.text.isNotBlank() }.map { TimedText(it.startMs, it.text) }
    }

    /** NetEase YRC: `[lineStart,lineDur](wordStart,wordDur,0)word…`, absolute times. */
    fun parseYrc(content: String): LyricDoc {
        val lines = ArrayList<LyricLine>()
        for (raw in content.replace("\r", "").split('\n')) {
            val line = raw.trim()
            if (line.startsWith("{")) {
                creditLine(line)?.let { lines.add(it) }
                continue
            }
            val m = YRC_LINE.matchEntire(line) ?: continue
            val words = YRC_WORD.findAll(m.groupValues[3]).map {
                LyricWord(it.groupValues[1].toLong(), it.groupValues[2].toLong(), it.groupValues[3])
            }.filter { it.text.isNotEmpty() }.toList()
            addWordLine(lines, m.groupValues[1].toLong(), words, m.groupValues[3])
        }
        lines.sortBy { it.startMs }
        return LyricDoc(lines)
    }

    /** QQ QRC (plain, after decryption): `[lineStart,lineDur]word(wordStart,wordDur)…`, absolute times. */
    fun parseQrc(content: String): LyricDoc {
        val body = QRC_CONTENT.find(content)?.groupValues?.get(1)?.let(::unescapeXml) ?: content
        val lines = ArrayList<LyricLine>()
        for (raw in body.replace("\r", "").split('\n')) {
            val m = YRC_LINE.matchEntire(raw.trim()) ?: continue
            val words = QRC_WORD.findAll(m.groupValues[3]).map {
                LyricWord(it.groupValues[2].toLong(), it.groupValues[3].toLong(), it.groupValues[1])
            }.filter { it.text.isNotEmpty() }.toList()
            addWordLine(lines, m.groupValues[1].toLong(), words, m.groupValues[3])
        }
        lines.sortBy { it.startMs }
        return LyricDoc(lines)
    }

    /** True when decrypted QQ lyrics are word-timed QRC rather than LRC. */
    fun isQrc(content: String): Boolean =
        content.contains("LyricContent=") || Regex("""^\[\d+,\d+].*\(\d+,\d+\)""", RegexOption.MULTILINE).containsMatchIn(content)

    /** Kugou KRC (plain, after decryption): `[lineStart,lineDur]<offset,dur,0>word…`, word offsets relative to the line. */
    fun parseKrc(content: String): LyricDoc {
        val lines = ArrayList<LyricLine>()
        for (raw in content.replace("\r", "").split('\n')) {
            val m = YRC_LINE.matchEntire(raw.trim()) ?: continue
            val lineStart = m.groupValues[1].toLong()
            val words = KRC_WORD.findAll(m.groupValues[3]).map {
                LyricWord(lineStart + it.groupValues[1].toLong(), it.groupValues[2].toLong(), it.groupValues[3])
            }.filter { it.text.isNotEmpty() }.toList()
            addWordLine(lines, lineStart, words, m.groupValues[3])
        }
        lines.sortBy { it.startMs }
        return LyricDoc(lines)
    }

    /**
     * Start time of every `[start,dur]` KRC line in file order, and whether it has any text.
     * KRC translation rows are indexed by this list; romaji rows by its non-blank entries.
     */
    fun krcLineStarts(content: String): List<Pair<Long, Boolean>> =
        content.replace("\r", "").split('\n').mapNotNull { raw ->
            val m = YRC_LINE.matchEntire(raw.trim()) ?: return@mapNotNull null
            val text = KRC_WORD.findAll(m.groupValues[3]).joinToString("") { it.groupValues[3] }
                .ifEmpty { m.groupValues[3].replace(Regex("""<\d+,\d+,-?\d+>"""), "") }
            m.groupValues[1].toLong() to text.isNotBlank()
        }

    /** The base64 `[language:…]` tag of a KRC file (translation / romaji as JSON), if any. */
    fun krcLanguageTag(content: String): String? =
        Regex("""^\[language:([^\]]*)]""", RegexOption.MULTILINE).find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * TTML as used by the AMLL lyric database: `<p begin end>` lines of `<span begin end>` words;
     * `ttm:role="x-translation"` / `"x-roman"` spans carry the per-line translation / romaji.
     * Background-vocal spans (`x-bg`) are left out.
     */
    fun parseTtml(content: String): LyricDoc {
        val lines = ArrayList<LyricLine>()
        val translation = ArrayList<TimedText>()
        val romaji = ArrayList<TimedText>()
        // Apple-style files keep translations / transliterations in <head>, keyed by itunes:key.
        val headTranslations = ttmlHeadTexts(content, "translation")
        val headRomaji = ttmlHeadTexts(content, "transliteration")
        val pRegex = Regex("""<p\b([^>]*)>([\s\S]*?)</p>""")
        for (p in pRegex.findAll(content)) {
            val pBegin = attr(p.groupValues[1], "begin")?.let(::ttmlTimeMs) ?: continue
            val pKey = attr(p.groupValues[1], "itunes:key")
            val body = p.groupValues[2]
            val words = ArrayList<LyricWord>()
            var trans: String? = null
            var roman: String? = null
            val plain = StringBuilder()
            var index = 0
            var pendingSpace = false
            while (index < body.length) {
                val open = body.indexOf("<span", index)
                val gap = if (open < 0) body.substring(index) else body.substring(index, open)
                val gapText = unescapeXml(gap.replace(Regex("<[^>]*>"), ""))
                if (gapText.isNotEmpty()) {
                    if (words.isEmpty()) plain.append(gapText)
                    if (gapText.isBlank()) pendingSpace = true
                    else if (words.isNotEmpty()) {
                        val last = words.removeAt(words.size - 1)
                        words.add(last.copy(text = last.text + gapText))
                    }
                }
                if (open < 0) break
                val tagEnd = body.indexOf('>', open)
                if (tagEnd < 0) break
                val attrs = body.substring(open + 5, tagEnd)
                val close = matchingSpanEnd(body, tagEnd + 1)
                val inner = body.substring(tagEnd + 1, close)
                index = min(body.length, close + "</span>".length)
                val role = attr(attrs, "ttm:role") ?: attr(attrs, "role")
                when (role) {
                    "x-translation" -> trans = unescapeXml(inner.replace(Regex("<[^>]*>"), "")).trim()
                    "x-roman" -> roman = unescapeXml(inner.replace(Regex("<[^>]*>"), "")).trim()
                    "x-bg" -> {}
                    else -> {
                        val begin = attr(attrs, "begin")?.let(::ttmlTimeMs)
                        val end = attr(attrs, "end")?.let(::ttmlTimeMs)
                        val text = unescapeXml(inner.replace(Regex("<[^>]*>"), ""))
                        if (begin != null && end != null && text.isNotEmpty()) {
                            if (pendingSpace && words.isNotEmpty()) {
                                val last = words.removeAt(words.size - 1)
                                words.add(last.copy(text = last.text + " "))
                            }
                            words.add(LyricWord(begin, max(1, end - begin), text))
                        }
                    }
                }
                pendingSpace = false
            }
            val trimmed = trimLastWord(words)
            val text = (if (trimmed.isNotEmpty()) trimmed.joinToString("") { it.text } else plain.toString()).trim()
            if (text.isEmpty()) continue
            val start = min(pBegin, trimmed.firstOrNull()?.startMs ?: pBegin)
            lines.add(LyricLine(start, if (trimmed.size > 0) trimmed else emptyList(), text))
            (trans ?: pKey?.let { headTranslations[it] })?.takeIf { it.isNotEmpty() }?.let { translation.add(TimedText(start, it)) }
            (roman ?: pKey?.let { headRomaji[it] })?.takeIf { it.isNotEmpty() }?.let { romaji.add(TimedText(start, it)) }
        }
        lines.sortBy { it.startMs }
        return LyricDoc(lines, translation, romaji)
    }

    private fun ttmlHeadTexts(content: String, block: String): Map<String, String> {
        val out = HashMap<String, String>()
        val blocks = Regex("""<$block\b[^>]*>([\s\S]*?)</$block>""").findAll(content)
        for (b in blocks) {
            for (t in Regex("""<text\b([^>]*)>([\s\S]*?)</text>""").findAll(b.groupValues[1])) {
                val key = attr(t.groupValues[1], "for") ?: continue
                val text = unescapeXml(t.groupValues[2].replace(Regex("<[^>]*>"), "")).trim()
                if (text.isNotEmpty() && key !in out) out[key] = text
            }
        }
        return out
    }

    // ---------- output for Folia ----------

    /**
     * Folia's lyric container (`[awlrc:lrc:…,tlrc:…,rlrc:…,awlrc:…]`, from LX Music / Kugou exports):
     * one string carrying word timing, translation and romaji. Folia reads it before guessing formats.
     * Every track uses the same `[mm:ss.mmm]` stamps, and translation / romaji are re-keyed to the
     * exact start of the line they belong to — Folia matches them by exact time.
     */
    fun toFoliaContainer(doc: LyricDoc): String {
        val lines = doc.lines.filter { it.text.isNotBlank() }
        val wordByWord = doc.wordByWord
        val lrc = StringBuilder()
        // Line-timed lyrics keep their blank lines: in LRC they mark an instrumental break, and
        // without them the last sung line would stay lit until the next one.
        if (!wordByWord) {
            for (line in doc.lines) lrc.append(stamp(line.startMs)).append(clean(line.text)).append('\n')
        }
        val awlrc = StringBuilder()
        val lineStarts = ArrayList<Long>(lines.size)
        for ((index, line) in lines.withIndex()) {
            val words = line.words
            val start = if (words.isNotEmpty()) min(line.startMs, words.first().startMs) else line.startMs
            lineStarts.add(start)
            if (wordByWord) lrc.append(stamp(start)).append(clean(line.text)).append('\n')
            if (wordByWord) {
                awlrc.append(stamp(start))
                if (words.isEmpty()) {
                    val next = lines.getOrNull(index + 1)?.startMs ?: (start + 3000)
                    awlrc.append("<0,").append((next - start).coerceIn(1, 10_000)).append('>').append(clean(line.text))
                } else {
                    for (w in words) {
                        awlrc.append('<').append(max(0, w.startMs - start)).append(',').append(max(1, w.durationMs)).append('>')
                            .append(clean(w.text))
                    }
                }
                awlrc.append('\n')
            }
        }
        val parts = ArrayList<String>()
        parts.add("lrc:" + b64(lrc.toString()))
        alignedTrack(lineStarts, doc.translation)?.let { parts.add("tlrc:" + b64(it)) }
        alignedTrack(lineStarts, doc.romaji)?.let { parts.add("rlrc:" + b64(it)) }
        if (wordByWord) parts.add("awlrc:" + b64(awlrc.toString()))
        return "[awlrc:" + parts.joinToString(",") + "]"
    }

    /** Each track entry goes to the nearest line start within 1 s (each line takes at most one). */
    private fun alignedTrack(lineStarts: List<Long>, track: List<TimedText>): String? {
        if (track.isEmpty() || lineStarts.isEmpty()) return null
        val assigned = arrayOfNulls<String>(lineStarts.size)
        for (entry in track.sortedBy { it.startMs }) {
            if (entry.text.isBlank() || entry.text.trim() == "//") continue
            var best = -1
            var bestDiff = Long.MAX_VALUE
            for (i in lineStarts.indices) {
                val diff = abs(lineStarts[i] - entry.startMs)
                if (diff < bestDiff && assigned[i] == null) {
                    bestDiff = diff
                    best = i
                }
            }
            if (best >= 0 && bestDiff <= 1000) assigned[best] = entry.text.trim()
        }
        if (assigned.all { it == null }) return null
        val out = StringBuilder()
        for (i in lineStarts.indices) {
            val text = assigned[i] ?: continue
            out.append(stamp(lineStarts[i])).append(clean(text)).append('\n')
        }
        return out.toString()
    }

    // ---------- text conversion ----------

    fun hasKana(doc: LyricDoc): Boolean = doc.lines.any { KANA.containsMatchIn(it.text) }

    /**
     * Applies [convert] (Simplified → Traditional) to the original lines and the translation.
     * Japanese lyrics (any kana) keep their original text; romaji is never touched.
     */
    fun convertChinese(doc: LyricDoc, convert: (String) -> String): LyricDoc {
        val lines = if (hasKana(doc)) doc.lines else doc.lines.map { line ->
            LyricLine(line.startMs, line.words.map { it.copy(text = convert(it.text)) }, convert(line.text))
        }
        return LyricDoc(lines, doc.translation.map { it.copy(text = convert(it.text)) }, doc.romaji)
    }

    // ---------- decryption ----------

    private val KRC_KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
    )

    /** Kugou KRC download (base64 content): drop the 4-byte "krc1" header, XOR with the key, inflate. */
    fun decryptKrc(base64: String): String {
        val raw = Base64.getMimeDecoder().decode(base64)
        require(raw.size > 4) { "KRC too short" }
        val data = ByteArray(raw.size - 4) { i -> (raw[i + 4].toInt() xor KRC_KEY[i % KRC_KEY.size].toInt()).toByte() }
        return inflate(data)
    }

    /** QQ Music QRC (hex): QQ's own 3DES variant on 8-byte blocks, then zlib. */
    fun decryptQrc(hex: String): String {
        val clean = hex.trim()
        require(clean.length >= 16 && clean.length % 2 == 0) { "QRC hex has odd length" }
        val data = ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val out = ByteArray(data.size - data.size % 8)
        var i = 0
        while (i + 8 <= data.size) {
            QrcCipher.decryptBlock(data, i, out, i)
            i += 8
        }
        return inflate(out)
    }

    /** zlib inflate that ignores padding after the end of the stream. */
    private fun inflate(data: ByteArray): String {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buffer, 0, n)
            }
            return out.toString("UTF-8")
        } finally {
            inflater.end()
        }
    }

    // ---------- helpers ----------

    private fun addWordLine(lines: MutableList<LyricLine>, lineStart: Long, words: List<LyricWord>, rawBody: String) {
        if (words.isNotEmpty()) {
            val text = words.joinToString("") { it.text }
            if (text.isNotBlank()) lines.add(LyricLine(min(lineStart, words.first().startMs), words, text.trim()))
        } else {
            val text = rawBody.replace(Regex("""\(\d+,\d+(?:,-?\d+)?\)|<\d+,\d+,-?\d+>"""), "").trim()
            if (text.isNotEmpty()) lines.add(LyricLine(lineStart, emptyList(), text))
        }
    }

    private fun creditLine(json: String): LyricLine? {
        val time = JSON_TIME.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val text = JSON_TEXT.findAll(json).joinToString("") { unescapeJson(it.groupValues[1]) }.trim()
        return if (text.isEmpty()) null else LyricLine(time, emptyList(), text)
    }

    private fun trimLastWord(words: List<LyricWord>): List<LyricWord> {
        if (words.isEmpty()) return words
        val last = words.last()
        return words.dropLast(1) + last.copy(text = last.text.trimEnd())
    }

    private fun lrcTimeMs(m: MatchResult): Long {
        val minutes = m.groupValues[1].toLong()
        val seconds = m.groupValues[2].toLong()
        val fraction = m.groupValues[3]
        val ms = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.substring(0, 3).toLong()
        }
        return minutes * 60_000 + seconds * 1000 + ms
    }

    /** TTML clock values: `12.345s`, `ss.mmm`, `mm:ss.mmm`, `hh:mm:ss.mmm`. */
    fun ttmlTimeMs(value: String): Long? {
        val v = value.trim()
        if (v.endsWith("ms")) return v.dropLast(2).toDoubleOrNull()?.toLong()
        if (v.endsWith("s")) return v.dropLast(1).toDoubleOrNull()?.let { (it * 1000).toLong() }
        val parts = v.split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var total = 0.0
        for (part in parts) {
            val n = part.toDoubleOrNull() ?: return null
            total = total * 60 + n
        }
        return Math.round(total * 1000)
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("""(?:^|\s)""" + Regex.escape(name) + """\s*=\s*"([^"]*)"""").find(attrs)?.groupValues?.get(1)

    /** Index of the `</span>` closing the span whose content starts at [from] (spans can nest). */
    private fun matchingSpanEnd(body: String, from: Int): Int {
        var depth = 1
        var i = from
        while (i < body.length) {
            val nextOpen = body.indexOf("<span", i)
            val nextClose = body.indexOf("</span>", i)
            if (nextClose < 0) return body.length
            if (nextOpen in 0 until nextClose) {
                depth++
                i = nextOpen + 5
            } else {
                depth--
                if (depth == 0) return nextClose
                i = nextClose + 7
            }
        }
        return body.length
    }

    fun unescapeXml(s: String): String {
        if (!s.contains('&')) return s
        return Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);""").replace(s) {
            when (val e = it.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt()
                    String(Character.toChars(code))
                }
            }
        }
    }

    private fun unescapeJson(s: String): String {
        if (!s.contains('\\')) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> {}
                    'u' -> if (i + 5 < s.length) {
                        out.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    }
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Text inside a track line: no line breaks, and no '<' (it would start a word marker). */
    private fun clean(text: String): String = text.replace('\n', ' ').replace('\r', ' ').replace('<', '＜')

    private fun stamp(ms: Long): String {
        val t = max(0, ms)
        val minutes = t / 60_000
        val seconds = (t / 1000) % 60
        val millis = t % 1000
        return "[" + (if (minutes < 10) "0" else "") + minutes + ":" + (if (seconds < 10) "0" else "") + seconds + "." +
            millis.toString().padStart(3, '0') + "]"
    }

    private fun b64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
}

/**
 * QQ Music's 3DES variant (not standard DES: its own bit order and two altered S-box entries),
 * decrypt direction only. Specification as implemented by QQMusicDecoder / LDDC / Folia.
 */
internal object QrcCipher {
    private val KEY = "!@#)(*\$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)

    private val SBOX = arrayOf(
        intArrayOf(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13),
        intArrayOf(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9),
        intArrayOf(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12),
        intArrayOf(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14),
        intArrayOf(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3),
        intArrayOf(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13),
        intArrayOf(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12),
        intArrayOf(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11),
    )
    private val IP0 = intArrayOf(57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3, 61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7)
    private val IP1 = intArrayOf(56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6)
    private val PERM = intArrayOf(15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9, 1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24)
    private val KEY_SHIFT = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val KEY_PERM_C = intArrayOf(56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35)
    private val KEY_PERM_D = intArrayOf(62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3)
    private val KEY_COMPRESSION = intArrayOf(13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36,
        46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31)

    // Decrypt = key 3 decrypt, key 2 encrypt, key 1 decrypt.
    private val SCHEDULES: Array<Array<IntArray>> = arrayOf(
        keySchedule(KEY, 16, decrypt = true),
        keySchedule(KEY, 8, decrypt = false),
        keySchedule(KEY, 0, decrypt = true),
    )

    fun decryptBlock(input: ByteArray, inOffset: Int, output: ByteArray, outOffset: Int) {
        var block = input.copyOfRange(inOffset, inOffset + 8)
        for (schedule in SCHEDULES) block = crypt(block, schedule)
        System.arraycopy(block, 0, output, outOffset, 8)
    }

    private fun bitnum(a: ByteArray, offset: Int, b: Int, c: Int): Int =
        (((a[offset + (b / 32) * 4 + 3 - (b % 32) / 8].toInt() and 0xff) ushr (7 - b % 8)) and 1) shl c

    private fun bitnumIntr(a: Int, b: Int, c: Int): Int = ((a ushr (31 - b)) and 1) shl c

    private fun bitnumIntl(a: Int, b: Int, c: Int): Int = ((a shl b) and 0x80000000.toInt()) ushr c

    private fun sboxBit(a: Int): Int = (a and 32) or ((a and 31) ushr 1) or ((a and 1) shl 4)

    private fun crypt(input: ByteArray, key: Array<IntArray>): ByteArray {
        var s0 = 0
        var s1 = 0
        for (i in 0 until 32) {
            s0 = s0 or bitnum(input, 0, IP0[i], 31 - i)
            s1 = s1 or bitnum(input, 0, IP1[i], 31 - i)
        }
        for (round in 0 until 15) {
            val previous = s1
            s1 = f(s1, key[round]) xor s0
            s0 = previous
        }
        s0 = f(s1, key[15]) xor s0

        val out = ByteArray(8)
        for (b in 0 until 8) {
            val v = bitnumIntr(s1, b, 7) or bitnumIntr(s0, b, 6) or bitnumIntr(s1, b + 8, 5) or bitnumIntr(s0, b + 8, 4) or
                bitnumIntr(s1, b + 16, 3) or bitnumIntr(s0, b + 16, 2) or bitnumIntr(s1, b + 24, 1) or bitnumIntr(s0, b + 24, 0)
            out[(b + 4) % 8] = v.toByte()
        }
        return out
    }

    private fun f(state: Int, key: IntArray): Int {
        val t1 = bitnumIntl(state, 31, 0) or ((state and 0xf0000000.toInt()) ushr 1) or bitnumIntl(state, 4, 5) or
            bitnumIntl(state, 3, 6) or ((state and 0x0f000000) ushr 3) or bitnumIntl(state, 8, 11) or
            bitnumIntl(state, 7, 12) or ((state and 0x00f00000) ushr 5) or bitnumIntl(state, 12, 17) or
            bitnumIntl(state, 11, 18) or ((state and 0x000f0000) ushr 7) or bitnumIntl(state, 16, 23)
        val t2 = bitnumIntl(state, 15, 0) or ((state and 0x0000f000) shl 15) or bitnumIntl(state, 20, 5) or
            bitnumIntl(state, 19, 6) or ((state and 0x00000f00) shl 13) or bitnumIntl(state, 24, 11) or
            bitnumIntl(state, 23, 12) or ((state and 0x000000f0) shl 11) or bitnumIntl(state, 28, 17) or
            bitnumIntl(state, 27, 18) or ((state and 0x0000000f) shl 9) or bitnumIntl(state, 0, 23)
        val l = intArrayOf(
            ((t1 ushr 24) and 0xff) xor key[0], ((t1 ushr 16) and 0xff) xor key[1], ((t1 ushr 8) and 0xff) xor key[2],
            ((t2 ushr 24) and 0xff) xor key[3], ((t2 ushr 16) and 0xff) xor key[4], ((t2 ushr 8) and 0xff) xor key[5],
        )
        val s = (SBOX[0][sboxBit(l[0] ushr 2)] shl 28) or
            (SBOX[1][sboxBit(((l[0] and 0x03) shl 4) or (l[1] ushr 4))] shl 24) or
            (SBOX[2][sboxBit(((l[1] and 0x0f) shl 2) or (l[2] ushr 6))] shl 20) or
            (SBOX[3][sboxBit(l[2] and 0x3f)] shl 16) or
            (SBOX[4][sboxBit(l[3] ushr 2)] shl 12) or
            (SBOX[5][sboxBit(((l[3] and 0x03) shl 4) or (l[4] ushr 4))] shl 8) or
            (SBOX[6][sboxBit(((l[4] and 0x0f) shl 2) or (l[5] ushr 6))] shl 4) or
            SBOX[7][sboxBit(l[5] and 0x3f)]
        var result = 0
        for (i in 0 until 32) result = result or bitnumIntl(s, PERM[i], i)
        return result
    }

    private fun keySchedule(key: ByteArray, offset: Int, decrypt: Boolean): Array<IntArray> {
        val schedule = Array(16) { IntArray(6) }
        var c = 0
        var d = 0
        for (i in 0 until 28) {
            c = c or bitnum(key, offset, KEY_PERM_C[i], 31 - i)
            d = d or bitnum(key, offset, KEY_PERM_D[i], 31 - i)
        }
        for (i in 0 until 16) {
            val shift = KEY_SHIFT[i]
            c = ((c shl shift) or (c ushr (28 - shift))) and 0xfffffff0.toInt()
            d = ((d shl shift) or (d ushr (28 - shift))) and 0xfffffff0.toInt()
            val target = if (decrypt) 15 - i else i
            for (j in 0 until 6) schedule[target][j] = 0
            for (j in 0 until 24) schedule[target][j / 8] = schedule[target][j / 8] or bitnumIntr(c, KEY_COMPRESSION[j], 7 - (j % 8))
            for (j in 24 until 48) schedule[target][j / 8] = schedule[target][j / 8] or bitnumIntr(d, KEY_COMPRESSION[j] - 27, 7 - (j % 8))
        }
        return schedule
    }
}
