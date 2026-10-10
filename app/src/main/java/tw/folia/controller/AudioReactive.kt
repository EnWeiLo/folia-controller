package tw.folia.controller

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import kotlin.math.hypot
import kotlin.math.log10

/**
 * "跟著音樂律動" on Android: reads the spectrum of whatever the phone is playing through the system
 * audio visualizer (session 0 = the output mix; needs the RECORD_AUDIO permission, nothing is
 * recorded) and hands fc-audio.js 1024 bytes in Folia's scale (≈21.5 Hz per bin, 0…255), about
 * 30 times a second. Same output as the Windows app's AudioCapture.
 */
class AudioReactive(private val onFrame: (base64: String, playing: Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var visualizer: Visualizer? = null
    private var fft = ByteArray(0)
    private var smoothed = DoubleArray(0)
    private val bytes = ByteArray(BINS)
    private var ticks = 0

    val running get() = visualizer != null

    /** Starts listening; returns an error message if the system refuses. */
    fun start(): String? {
        if (visualizer != null) return null
        val v = try {
            Visualizer(0).apply {
                enabled = false
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                enabled = true
            }
        } catch (e: Throwable) {
            return e.message ?: e.javaClass.simpleName
        }
        visualizer = v
        fft = ByteArray(v.captureSize)
        smoothed = DoubleArray(v.captureSize / 2)
        val t = HandlerThread("folia-audio").also { it.start() }
        thread = t
        worker = Handler(t.looper).also { it.post(tick) }
        return null
    }

    fun stop() {
        worker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        worker = null
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Throwable) {
        }
        visualizer = null
    }

    private val tick = object : Runnable {
        override fun run() {
            val v = visualizer ?: return
            ticks++
            try {
                if (!MediaHub.isPlaying()) {
                    smoothed.fill(0.0)
                    // A slow heartbeat so the page knows the feature is on (Folia's idle breathing).
                    if (ticks % 15 == 0) main.post { onFrame("", false) }
                } else if (v.getFft(fft) == Visualizer.SUCCESS) {
                    analyse(v.samplingRate / 1000.0)
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    main.post { onFrame(b64, true) }
                }
            } catch (_: Throwable) {
            }
            worker?.postDelayed(this, TICK_MS)
        }
    }

    private fun analyse(sampleRate: Double) {
        val n = fft.size
        val half = n / 2
        // fft = [Re0, Re(n/2), Re1, Im1, Re2, Im2, …] as signed bytes.
        for (k in 0 until half) {
            val magnitude = when (k) {
                0 -> kotlin.math.abs(fft[0].toDouble())
                else -> hypot(fft[2 * k].toDouble(), fft[2 * k + 1].toDouble())
            } / 128.0
            smoothed[k] = 0.8 * smoothed[k] + 0.2 * magnitude
        }
        val hzPerBin = sampleRate / n
        for (i in 0 until BINS) {
            val src = i * FOLIA_HZ_PER_BIN / hzPerBin
            val lo = src.toInt()
            val value = if (lo >= half - 1) 0.0 else {
                val t = src - lo
                smoothed[lo] * (1 - t) + smoothed[lo + 1] * t
            }
            val db = if (value > 0) 20 * log10(value) else -200.0
            // The visualizer's normalized spectrum runs ~0 dB at full scale; spread -70…0 dB over 0…255.
            bytes[i] = ((db + 70) * 255 / 70).coerceIn(0.0, 255.0).toInt().toByte()
        }
    }

    companion object {
        private const val BINS = 1024
        private const val TICK_MS = 33L
        private const val FOLIA_HZ_PER_BIN = 44100.0 / 2048
    }
}
