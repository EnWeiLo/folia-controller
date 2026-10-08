package tw.folia.controller

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale

/**
 * Serves Folia's web build (bundled under assets/folia/) at https://appassets.androidplatform.net/
 * so the page runs exactly like the hosted web version, with two changes to index.html:
 *  - folia-bridge.js is injected before any other script (replaces the Now Playing WebSocket);
 *  - the viewport makes 1 CSS px = 1 physical pixel, so Folia's OBS layout (built for a
 *    1080 / 1920 px wide canvas) renders sharp instead of being upscaled from ~400 px.
 * Service-worker scripts get a 404: a cached SW would serve stale bundles after an update.
 */
class FoliaAssetServer(private val context: Context) {
    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST"
        private const val ROOT = "folia"
    }

    private val bridgeScript: String by lazy {
        context.assets.open("folia-bridge.js").bufferedReader().use { it.readText() }
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

        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val scale = String.format(Locale.US, "%.4f", 1f / density)
        val viewport = "<meta name=\"viewport\" content=\"initial-scale=$scale, minimum-scale=$scale, " +
            "maximum-scale=$scale, user-scalable=no, viewport-fit=cover\" />"

        var out = Regex("""<meta\s+name=["']viewport["'][^>]*>""", RegexOption.IGNORE_CASE).replace(html, "")
        val headTag = Regex("""<head[^>]*>""", RegexOption.IGNORE_CASE).find(out)
        val injection = "$viewport<script>$bridgeScript</script>"
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
