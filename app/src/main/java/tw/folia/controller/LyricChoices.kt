package tw.folia.controller

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Lyrics the user picked by hand, remembered per song (title + artist), so the same song gets the
 * same lyrics next time. Twin of LyricChoices.cs.
 */
object LyricChoices {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("lyric_choices", Context.MODE_PRIVATE)
    }

    fun key(query: SongQuery): String {
        fun n(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        return n(LyricRepository.cleanTitle(query.title)) + "|" + n(LyricRepository.primaryArtist(query.artist))
    }

    /** (true, null) = "no lyrics for this song"; (false, candidate) = use this; (false, null) = nothing stored. */
    fun get(query: SongQuery): Pair<Boolean, LyricCandidate?> {
        val raw = prefs?.getString(key(query), null) ?: return false to null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return false to null
        if (o.optString("source") == "none") return true to null
        return false to LyricCandidate.fromJson(o)
    }

    fun has(query: SongQuery): Boolean = prefs?.contains(key(query)) == true

    fun set(query: SongQuery, candidate: LyricCandidate) {
        // LRCLIB search results carry the lyrics themselves; store only what is needed to fetch them again.
        val stored = candidate.copy(extra = candidate.extra - "synced")
        prefs?.edit()?.putString(key(query), stored.toJson().toString())?.apply()
    }

    fun setNone(query: SongQuery) {
        prefs?.edit()?.putString(key(query), JSONObject().put("source", "none").toString())?.apply()
    }

    fun remove(query: SongQuery) {
        prefs?.edit()?.remove(key(query))?.apply()
    }
}
