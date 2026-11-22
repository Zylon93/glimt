package se.pontus.glimt

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Kollar GitHub Releases efter en nyare glimt.apk och installerar den. */
object Updater {
    data class Release(val versionCode: Int, val apkUrl: String) {
        val versionName: String get() = "1.0.$versionCode"
    }

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return@withContext null
        val json = JSONObject(
            Net.bytes(
                "https://api.github.com/repos/$repo/releases/latest",
                headers = mapOf("Accept" to "application/vnd.github+json"),
            ).decodeToString()
        )
        // Taggen är v1.0.<bygg-nummer>, och bygg-numret är också versionCode.
        val code = json.optString("tag_name").substringAfterLast('.').toIntOrNull() ?: return@withContext null
        if (code <= BuildConfig.VERSION_CODE) return@withContext null
        val assets = json.optJSONArray("assets") ?: return@withContext null
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
        }
        url?.let { Release(code, it) }
    }

    suspend fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
            val file = File(dir, "glimt.apk")
            val conn = Net.open(release.apkUrl, readTimeoutMs = 60_000)
            try {
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (file.length() < 100_000) throw IOException("Nedladdningen blev ofullständig")
            file
        }

    fun install(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                activity,
                "Tillåt Glimt att installera appar, gå tillbaka och tryck Uppdatera igen",
                Toast.LENGTH_LONG,
            ).show()
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            } catch (e: ActivityNotFoundException) {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, "TV:n hittar ingen installerare för APK-filer", Toast.LENGTH_LONG).show()
        }
    }
}
