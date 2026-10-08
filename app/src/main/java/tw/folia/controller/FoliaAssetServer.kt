package tw.folia.controller

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale

/** Per-load page options decided on the Android side. */
data class PageSettings(
    /** Fraction of physical pixels the page lays out and renders canvases at (1 = native). */
    val renderScale: Float,
    /** Folia's own rAF cap: "off", "60", "90" or "120". */
    val frameCap: String,
    val showFps: Boolean,
    val displayHz: Float,
)

/**
 * Serves Folia's web build (bundled under assets/folia/) at https://appassets.androidplatform.net/
 * so the page runs exactly like the hosted web version, with two changes to index.html:
 *  - folia-bridge.js is injected before any other script (replaces the Now Playing WebSocket);
 *  - the viewport makes 1 CSS px = 1 physical pixel × renderScale, so Folia's OBS layout (built for
 *    a 1080 / 1920 px wide canvas) renders sharp instead of being upscaled from ~400 px. Below 1,
 *    every canvas renders fewer pixels and is scaled up (the quality / smoothness trade-off);
 *  - Folia's frame-rate cap is written to localStorage before Folia reads it at startup.
 * Service-worker scripts get a 404: a cached SW would serve stale bundles after an update.
 */
class FoliaAssetServer(
    private val context: Context,
    private val pageSettings: () -> PageSettings,
) {
    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST"
        private const val ROOT = "folia"
    }

    private val bridgeScript: String by lazy {
        context.assets.open("folia-bridge.js").bufferedReader().use { it.readText() }
    }
    private val fpsScript: String by lazy {
        context.assets.open("folia-fps.js").bufferedReader().use { it.readText() }
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? = intercept(request.url)

    fun intercept(url: Uri): WebResourceResponse? {
        if (url.host != HOST) return null
        var path = url.path.orEmpty()
        if (path.isEmpty() || path == "/") path = "/index.html"
        if (path.contains("..")) return notFound()
        val name = path.substringAfterLast('/')

        if (name.endsWith("sw.js") || name == "registerSW.js" || name.startsWith("workbox-")) return notFound()
        if (path == "/index.html") return indexHtml()

        return try {
            val stream = context.assets.open(ROOT + path)
            WebResourceResponse(mimeType(name), charset(name), 200, "OK", headers(), stream)
        } catch (e: IOException) {
            // Single-page-app fallback for extension-less routes.
            if (!name.contains('.')) indexHtml() else notFound()
        }
    }

    private fun indexHtml(): WebResourceResponse {
        val html = try {
            context.assets.open("$ROOT/index.html").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            return textResponse(
                "<html><body style='background:#09090b;color:#eee;font-family:sans-serif;padding:24px'>" +
                    "<h3>找不到 Folia 網頁資源</h3><p>這個 APK 打包時沒有包含 assets/folia。請用 GitHub Actions 重新建置。</p></body></html>",
                "text/html",
            )
        }

        val settings = pageSettings()
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val renderScale = settings.renderScale.coerceIn(0.4f, 1f)
        val scale = String.format(Locale.US, "%.4f", 1f / (density * renderScale))
        val viewport = "<meta name=\"viewport\" content=\"initial-scale=$scale, minimum-scale=$scale, " +
            "maximum-scale=$scale, user-scalable=no, viewport-fit=cover\" />"

        var out = Regex("""<meta\s+name=["']viewport["'][^>]*>""", RegexOption.IGNORE_CASE).replace(html, "")
        val headTag = Regex("""<head[^>]*>""", RegexOption.IGNORE_CASE).find(out)
        val config = "window.__FOLIA_ANDROID_CONFIG__={frameCap:'${settings.frameCap}',showFps:${settings.showFps}," +
            "cssPerDp:${String.format(Locale.US, "%.3f", density * renderScale)}," +
            "displayHz:${String.format(Locale.US, "%.1f", settings.displayHz)}};" +
            "try{localStorage.setItem('visualizer_frame_rate','${settings.frameCap}')}catch(e){}"
        val fps = if (settings.showFps) "<script>$fpsScript</script>" else ""
        val injection = "$viewport<script>$config</script><script>$bridgeScript</script>$fps"
        out = if (headTag != null) {
            out.substring(0, headTag.range.last + 1) + injection + out.substring(headTag.range.last + 1)
        } else {
            injection + out
        }
        return textResponse(out, "text/html")
    }

    private fun textResponse(body: String, mime: String) =
        WebResourceResponse(mime, "utf-8", 200, "OK", headers(), ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))

    private fun notFound() =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers(), ByteArrayInputStream(ByteArray(0)))

    private fun headers() = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "no-cache",
    )

    private fun charset(name: String): String? = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "html", "htm", "js", "mjs", "css", "json", "svg", "txt", "map", "webmanifest" -> "utf-8"
        else -> null
    }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "webmanifest" -> "application/manifest+json"
        "wasm" -> "application/wasm"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "avif" -> "image/avif"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}
