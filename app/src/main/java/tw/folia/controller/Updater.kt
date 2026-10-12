package tw.folia.controller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自動更新: looks at this app's latest GitHub release and, when there is a newer build, downloads its
 * APK and hands it to Android's installer (one confirmation tap). Builds are signed with one fixed
 * key, so the new APK installs over the old one and keeps all settings.
 */
object Updater {
    private const val API = "https://api.github.com/repos/EnWeiLo/folia-controller/releases/latest"
    private const val ACTION_STATUS = "tw.folia.controller.UPDATE_STATUS"
    private val main = Handler(Looper.getMainLooper())

    class Release(val build: Int, val url: String, val page: String)

    fun currentBuild(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Exception) {
        0L
    }

    /** Calls [done] on the main thread with the newer release (or null) and whether GitHub could be reached. */
    fun check(context: Context, done: (release: Release?, reached: Boolean) -> Unit) {
        val have = currentBuild(context)
        Thread {
            var release: Release? = null
            var reached = false
            try {
                val connection = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Flowrics")
                }
                if (connection.responseCode == 200) {
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    reached = true
                    val tag = json.optString("tag_name")
                    val build = if (tag.startsWith("build-")) tag.removePrefix("build-").toIntOrNull() ?: 0 else 0
                    val assets = json.optJSONArray("assets")
                    var url = ""
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                                url = asset.optString("browser_download_url")
                                break
                            }
                        }
                    }
                    if (build > have && url.isNotEmpty()) release = Release(build, url, json.optString("html_url"))
                }
                connection.disconnect()
            } catch (e: Exception) {
                // Offline or rate limited: stay quiet.
            }
            main.post { done(release, reached) }
        }.start()
    }

    /** Downloads the APK; [cancelled] is polled. [done] gets the file, or null on failure / cancel. */
    fun download(context: Context, release: Release, progress: (Int) -> Unit, cancelled: () -> Boolean, done: (File?) -> Unit) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "Flowrics-${release.build}.apk")
        Thread {
            var ok = false
            try {
                val connection = (URL(release.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Flowrics")
                }
                if (connection.responseCode == 200) {
                    val total = connection.contentLengthLong
                    var got = 0L
                    var last = -1
                    connection.inputStream.use { input ->
                        target.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled()) throw InterruptedException()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                got += n
                                if (total > 0) {
                                    val percent = (got * 100 / total).toInt()
                                    if (percent != last) {
                                        last = percent
                                        main.post { progress(percent) }
                                    }
                                }
                            }
                        }
                    }
                    ok = (total <= 0 || got == total) && target.length() > 1_000_000
                }
                connection.disconnect()
            } catch (e: Exception) {
                ok = false
            }
            if (!ok) target.delete()
            main.post { done(if (ok) target else null) }
        }.start()
    }

    /** Hands the APK to the system installer; it asks the person to confirm. */
    fun install(context: Context, apk: File): Boolean = try {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("flowrics.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, StatusReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
        true
    } catch (e: Exception) {
        false
    }

    /** The installer's answer: show its confirmation screen when it needs one. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { context.startActivity(confirm) } catch (e: Exception) { }
                    }
                }
                else -> { /* success restarts the app; failures leave the old version running */ }
            }
        }
    }
}
