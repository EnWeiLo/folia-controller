package tw.folia.controller

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Lyric files in a folder the user picked (Android's folder picker; the app keeps permission to
 * that folder only). Files are matched by name: "Title.lrc", "Artist - Title.lrc" or
 * "Title - Artist.lrc". Twin of LocalLyrics.cs.
 */
object LocalLyrics {
    private val EXTENSIONS = setOf("lrc", "ttml", "yrc", "qrc", "krc", "txt")
    private const val REINDEX_MS = 120_000L

    private lateinit var appContext: Context
    private var prefs: SharedPreferences? = null

    private data class Entry(val name: String, val documentId: String)

    @Volatile private var index: List<Entry> = emptyList()
    @Volatile private var indexedAt = 0L

    fun init(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("folia_controller", Context.MODE_PRIVATE)
    }

    val folderUri: Uri? get() = prefs?.getString("local_lyrics_tree", null)?.let(Uri::parse)

    /** A short readable name for the settings menu. */
    val folderLabel: String?
        get() = folderUri?.let { uri ->
            runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifEmpty { "根目錄" } }.getOrNull() ?: uri.lastPathSegment
        }

    fun setFolder(uri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        folderUri?.takeIf { it != uri }?.let { old ->
            runCatching { appContext.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prefs?.edit()?.putString("local_lyrics_tree", uri.toString())?.apply()
        index = emptyList()
        indexedAt = 0
    }

    fun clearFolder() {
        folderUri?.let { runCatching { appContext.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        prefs?.edit()?.remove("local_lyrics_tree")?.apply()
        index = emptyList()
        indexedAt = 0
    }

    /** Matching files, best first, as picker candidates. Blocking. */
    fun candidates(query: SongQuery): List<LyricCandidate> {
        if (folderUri == null) return emptyList()
        val entries = entries()
        val title = LyricRepository.normalize(LyricRepository.cleanTitle(query.title))
        val artist = LyricRepository.normalize(LyricRepository.primaryArtist(query.artist))
        if (title.isEmpty()) return emptyList()
        return entries.mapNotNull { e ->
            val base = LyricRepository.normalize(e.name.substringBeforeLast('.'))
            val score = when {
                base == title -> 5
                artist.isNotEmpty() && (base == artist + title || base == title + artist) -> 6
                title.length >= 2 && base.contains(title) && artist.isNotEmpty() && base.contains(artist) -> 4
                title.length >= 2 && base.contains(title) -> 2
                else -> return@mapNotNull null
            }
            LyricCandidate("local", e.documentId, e.name, "", "", 0, emptyMap(), score, score >= 4)
        }.sortedByDescending { it.score }
    }

    /** The best matching file's lyrics, if any. Blocking. */
    fun find(query: SongQuery): LyricResult? {
        val best = candidates(query).firstOrNull { it.accepted } ?: return null
        val doc = load(best.id) ?: return null
        return LyricResult(doc, best)
    }

    /** Reads and parses one file by document id. Blocking. */
    fun load(documentId: String): LyricDoc? {
        val tree = folderUri ?: return null
        val name = index.firstOrNull { it.documentId == documentId }?.name ?: documentId
        val bytes = runCatching {
            appContext.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, documentId))?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val text = decode(bytes)
        return when (name.substringAfterLast('.', "").lowercase()) {
            "ttml" -> LyricFormats.parseTtml(text)
            "yrc" -> LyricFormats.parseYrc(text)
            "qrc" -> LyricFormats.parseQrc(text)
            "krc" -> LyricFormats.parseKrc(text)
            else -> LyricFormats.parseLrc(text).copy(rawLrc = text)
        }.takeUnless { it.isEmpty }
    }

    private fun entries(): List<Entry> {
        val now = System.currentTimeMillis()
        if (index.isNotEmpty() && now - indexedAt < REINDEX_MS) return index
        val tree = folderUri ?: return emptyList()
        val found = ArrayList<Entry>()
        runCatching { walk(tree, DocumentsContract.getTreeDocumentId(tree), 0, found) }
        index = found
        indexedAt = now
        return found
    }

    private fun walk(tree: Uri, parentId: String, depth: Int, out: MutableList<Entry>) {
        if (depth > 4 || out.size >= 5000) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        appContext.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(tree, id, depth + 1, out)
                } else if (name.substringAfterLast('.', "").lowercase() in EXTENSIONS) {
                    out.add(Entry(name, id))
                }
            }
        }
    }

    /** UTF-8 (with or without BOM), UTF-16 with BOM, else Big5, else GB18030 (common for older Chinese .lrc files). */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        for (name in listOf("UTF-8", "Big5", "GB18030")) {
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val decoded = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
            if (decoded != null) return decoded
        }
        return String(bytes, Charsets.UTF_8)
    }
}
