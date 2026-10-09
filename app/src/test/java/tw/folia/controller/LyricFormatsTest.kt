package tw.folia.controller

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LyricFormatsTest {
    /** Same fixtures as the Windows app's `--fixture-test`; both must produce the .expected files. */
    @Test
    fun fixturesMatchExpectedOutput() {
        val dir = File(System.getProperty("lyricFixtures") ?: "../tests/lyrics")
        val results = LyricFixtures.run(dir)
        results.forEach { println((if (it.ok) "PASS " else "FAIL ") + it.name + " — " + it.detail) }
        assertTrue("fixtures differ: " + results.filter { !it.ok }.joinToString { it.name }, results.all { it.ok })
        assertTrue("no fixtures found", results.size >= 8)
    }
}
