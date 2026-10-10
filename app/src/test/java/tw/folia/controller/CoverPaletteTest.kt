package tw.folia.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPaletteTest {
    @Test
    fun greyCoverIsNeutral() {
        val pixels = IntArray(32 * 32) { 0xFF808080.toInt() }
        assertEquals(CoverPalette.NEUTRAL, CoverPalette.from(pixels))
    }

    @Test
    fun blueCoverGivesBlueTints() {
        val pixels = IntArray(32 * 32) { 0xFF2050D0.toInt() }
        val palette = CoverPalette.from(pixels)
        assertNotEquals(CoverPalette.NEUTRAL, palette)
        val card = CoverPalette.hsv(palette.card)
        val light = CoverPalette.hsv(palette.highlight)
        assertTrue("card hue ${card[0]}", card[0] in 215.0..235.0)
        assertTrue("card is dark", card[2] < 0.25)
        assertTrue("highlight is light", light[2] > 0.9)
    }

    @Test
    fun demoSongIsWordTimedWithTranslation() {
        val doc = DemoSong.doc()
        assertTrue(doc.wordByWord)
        assertEquals(doc.lines.size, doc.translation.size)
    }
}
