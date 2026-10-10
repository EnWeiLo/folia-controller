package tw.folia.controller

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Colours for the info card, taken from the album cover. Pure Kotlin (pixels in, colours out) so
 * the Windows app (CoverPalette.cs) can do exactly the same.
 *
 * [card]: a dark tint of the cover's main hue, for the card behind white text.
 * [highlight]: a light, saturated tint, for the progress bar and sung words.
 */
data class CoverPalette(val card: Int, val highlight: Int) {
    companion object {
        val NEUTRAL = CoverPalette(card = 0xFF1C1C21.toInt(), highlight = 0xFFE8E4D8.toInt())

        /** [pixels]: ARGB pixels of a small (e.g. 32×32) copy of the cover. */
        fun from(pixels: IntArray): CoverPalette {
            var x = 0.0
            var y = 0.0
            var weight = 0.0
            var satSum = 0.0
            for (p in pixels) {
                if ((p ushr 24) < 128) continue
                val hsv = hsv(p)
                val s = hsv[1]
                val v = hsv[2]
                if (v < 0.15) continue
                // Vivid, bright pixels say most about a cover's colour; greys and shadows say little.
                val w = s * s * v
                if (w <= 0.0) continue
                val angle = Math.toRadians(hsv[0])
                x += cos(angle) * w
                y += sin(angle) * w
                weight += w
                satSum += s * w
            }
            if (weight < pixels.size * 0.02) return NEUTRAL
            var hue = Math.toDegrees(atan2(y, x))
            if (hue < 0) hue += 360.0
            val sat = satSum / weight
            // How strongly the hues agree: a rainbow cover averages out to a weak colour.
            val agreement = sqrt(x * x + y * y) / weight
            if (agreement < 0.25) return NEUTRAL
            val cardSat = min(0.55, sat * 0.8) * min(1.0, agreement * 1.4)
            val lightSat = (sat * 0.9).coerceIn(0.35, 0.6)
            return CoverPalette(
                card = fromHsv(hue, cardSat, 0.2),
                highlight = fromHsv(hue, lightSat, 0.97),
            )
        }

        fun hsv(argb: Int): DoubleArray {
            val r = ((argb shr 16) and 0xFF) / 255.0
            val g = ((argb shr 8) and 0xFF) / 255.0
            val b = (argb and 0xFF) / 255.0
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val d = mx - mn
            var h = when {
                d == 0.0 -> 0.0
                mx == r -> 60.0 * (((g - b) / d) % 6.0)
                mx == g -> 60.0 * ((b - r) / d + 2.0)
                else -> 60.0 * ((r - g) / d + 4.0)
            }
            if (h < 0) h += 360.0
            val s = if (mx == 0.0) 0.0 else d / mx
            return doubleArrayOf(h, s, mx)
        }

        fun fromHsv(h: Double, s: Double, v: Double): Int {
            val c = v * s
            val hp = (h % 360.0) / 60.0
            val xx = c * (1 - kotlin.math.abs(hp % 2 - 1))
            val (r1, g1, b1) = when {
                hp < 1 -> Triple(c, xx, 0.0)
                hp < 2 -> Triple(xx, c, 0.0)
                hp < 3 -> Triple(0.0, c, xx)
                hp < 4 -> Triple(0.0, xx, c)
                hp < 5 -> Triple(xx, 0.0, c)
                else -> Triple(c, 0.0, xx)
            }
            val m = v - c
            fun ch(value: Double) = Math.round((value + m) * 255).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
        }
    }
}
