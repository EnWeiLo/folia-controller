package tw.folia.controller

import java.io.File
import java.util.Base64

/**
 * Runs every fixture in tests/lyrics through LyricFormats and compares with `<name>.expected`
 * (the Windows smoke test checks the same files with the C# twin). With `update = true` the
 * expected files are rewritten instead.
 */
object LyricFixtures {
    data class Outcome(val name: String, val ok: Boolean, val detail: String)

    fun run(dir: File, update: Boolean = false): List<Outcome> {
        val results = ArrayList<Outcome>()
        val cases = dir.listFiles()!!.filter {
            it.extension in setOf("yrc", "qrc", "krc", "lrc", "ttml") && !it.name.endsWith(".trans.lrc") && !it.name.endsWith(".roma.lrc")
        }.sortedBy { it.name }
        for (file in cases) {
            val base = file.name.substringBeforeLast('.')
            val text = file.readText()
            var doc = when (file.extension) {
                "yrc" -> LyricFormats.parseYrc(text)
                "qrc" -> LyricFormats.parseQrc(text)
                "krc" -> LyricFormats.parseKrc(text)
                "ttml" -> LyricFormats.parseTtml(text)
                else -> LyricFormats.parseLrc(text)
            }
            File(dir, "$base.trans.lrc").takeIf { it.exists() }?.let { doc = doc.copy(translation = LyricFormats.parseTrack(it.readText())) }
            File(dir, "$base.roma.lrc").takeIf { it.exists() }?.let { doc = doc.copy(romaji = LyricFormats.parseTrack(it.readText())) }
            val container = LyricFormats.toFoliaContainer(doc)
            val expectedFile = File(dir, "$base.${file.extension}.expected")
            if (update || !expectedFile.exists()) {
                expectedFile.writeText(container)
                results.add(Outcome(file.name, true, "written"))
            } else {
                val ok = expectedFile.readText() == container
                results.add(Outcome(file.name, ok, if (ok) "matches" else "DIFFERS:\n$container"))
            }
        }
        val qrc = Regex(""""hex":\s*"([0-9A-F]+)",\s*"plain":\s*"((?:[^"\\]|\\.)*)"""").findAll(File(dir, "qrc-vectors.json").readText())
        for ((i, m) in qrc.withIndex()) {
            val plain = jsonString(m.groupValues[2])
            val got = runCatching { LyricFormats.decryptQrc(m.groupValues[1]) }.getOrElse { "ERROR $it" }
            results.add(Outcome("qrc vector $i", got == plain, if (got == plain) "decrypts" else "got: ${got.take(80)}"))
        }
        val krc = Regex(""""base64":\s*"([^"]+)",\s*"plain":\s*"((?:[^"\\]|\\.)*)"""").findAll(File(dir, "krc-vectors.json").readText())
        for ((i, m) in krc.withIndex()) {
            val plain = jsonString(m.groupValues[2])
            val got = runCatching { LyricFormats.decryptKrc(m.groupValues[1]) }.getOrElse { "ERROR $it" }
            results.add(Outcome("krc vector $i", got == plain, if (got == plain) "decrypts" else "got: ${got.take(80)}"))
        }
        return results
    }

    /** The container's tracks, decoded, for reading. */
    fun describe(container: String): String {
        val body = container.removePrefix("[awlrc:").removeSuffix("]")
        return body.split(',').joinToString("\n") { part ->
            val key = part.substringBefore(':')
            "--- $key ---\n" + String(Base64.getDecoder().decode(part.substringAfter(':')), Charsets.UTF_8).trimEnd()
        }
    }

    private fun jsonString(s: String): String =
        s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
}
