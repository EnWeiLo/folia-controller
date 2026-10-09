package tw.folia.controller

import org.junit.Test
import java.io.File

/**
 * Searches every lyric source for a few real songs (only with LIVE_LYRICS=1, as in CI) and writes
 * a report. Informational: sources can be slow or region-limited from a build server, so this
 * never fails the build. The Windows smoke test searches the same songs.
 */
class LiveLyricsTest {
    private val songs = listOf(
        SongQuery("晴天", "周杰倫", "葉惠美", 269_000),
        SongQuery("告白氣球", "周杰倫", "周杰倫的床邊故事", 215_000),
        SongQuery("夜に駆ける", "YOASOBI", "THE BOOK", 261_000),
        SongQuery("Yoru ni Kakeru", "YOASOBI", "", 261_000),
        SongQuery("Lemon", "米津玄師", "", 276_000),
        SongQuery("Blinding Lights", "The Weeknd", "After Hours", 200_040),
        // As Spotify on Android reports it: the AMLL database by Spotify track id.
        SongQuery("Blinding Lights", "The Weeknd", "After Hours", 200_040, spotifyId = "0VjIjW4GlUZAMYd2vXMi3b"),
    )

    @Test
    fun liveSearchReport() {
        if (System.getenv("LIVE_LYRICS") != "1") return
        val report = StringBuilder("== live lyric search (Android code on JVM) ==\n")
        for (song in songs) {
            try {
                val (result, candidates) = LyricRepository.find(song)
                report.append("${song.title} / ${song.artist}" + (song.spotifyId?.let { " (spotify $it)" } ?: "") + ": ")
                report.append(
                    if (result == null) "NONE"
                    else "${result.sourceLabel} [${result.candidate.title} / ${result.candidate.artist}] lines=${result.doc.lines.size} " +
                        "translation=${result.doc.translation.size} romaji=${result.doc.romaji.size}",
                ).append('\n')
                for ((source, group) in candidates.groupBy { it.source }) {
                    val top = group.first()
                    report.append("    $source: ${group.count { it.accepted }}/${group.size} accepted; top: ${top.title} / ${top.artist} score=${top.score}\n")
                }
                for (source in listOf("netease", "qq", "kugou", "lrclib").filter { s -> candidates.none { it.source == s } }) {
                    report.append("    $source: no results\n")
                }
                for ((source, amll) in listOf("netease" to "amll-ncm", "qq" to "amll-qq")) {
                    val c = candidates.firstOrNull { it.source == source && it.accepted } ?: continue
                    val doc = LyricRepository.fetch(c.copy(source = amll))
                    report.append("    $amll (${c.id}): " + (doc?.let { "lines=${it.lines.size} wordByWord=${it.wordByWord} translation=${it.translation.size}" } ?: "none") + "\n")
                }
                for (source in listOf("qq", "kugou")) {
                    val c = candidates.firstOrNull { it.source == source && it.accepted } ?: continue
                    val doc = LyricRepository.fetch(c)
                    report.append("    $source lyrics: " + (doc?.let { "lines=${it.lines.size} wordByWord=${it.wordByWord} translation=${it.translation.size} romaji=${it.romaji.size}" } ?: "none") + "\n")
                }
            } catch (e: Throwable) {
                report.append("${song.title}: ERROR $e\n")
            }
        }
        println(report)
        System.getProperty("liveReport")?.let { File(it).apply { parentFile?.mkdirs() }.writeText(report.toString()) }
    }
}
