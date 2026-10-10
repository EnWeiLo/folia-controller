package tw.folia.controller

/**
 * A made-up song (original lines, not real lyrics) for previewing the interface without a player:
 * the CI screenshots start the app with `--ez demo true`. Word-timed, with a translation track.
 */
object DemoSong {
    val LINES = listOf(
        "窗外的雨停了" to "The rain outside has stopped",
        "光落在桌上" to "Light falls across the table",
        "我把昨天折好" to "I fold up yesterday",
        "放進口袋裡" to "and slip it in my pocket",
        "慢慢地走" to "Walking slowly",
        "也會到遠方" to "still gets you far away",
    )
    const val LINE_MS = 4000L
    const val DURATION_MS = 200_000L
    /** Where the preview starts: mid-line, so word highlighting shows in a screenshot. */
    const val START_MS = 41_700L

    fun doc(): LyricDoc {
        val lines = ArrayList<LyricLine>()
        val translation = ArrayList<TimedText>()
        var k = 0
        while (k * LINE_MS < DURATION_MS) {
            val (text, english) = LINES[k % LINES.size]
            val start = k * LINE_MS
            val per = 3200L / text.length
            lines += LyricLine(start, text.mapIndexed { j, c -> LyricWord(start + j * per, per, c.toString()) }, text)
            translation += TimedText(start, english)
            k++
        }
        return LyricDoc(lines, translation)
    }
}
