package pl.fouroptima.mitvlauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/**
 * Aktualizacje z publicznego repo na GitHubie: każde wypchnięcie kodu buduje nowe wydanie,
 * a przycisk „Aktualizuj” pobiera je i otwiera instalator Androida.
 * Metody sieciowe są blokujące – wywoływać poza wątkiem głównym.
 */
class UpdateManager(private val context: Context) {

    /** Nowsza wersja albo null, gdy launcher jest aktualny. Rzuca IOException przy braku sieci. */
    @Throws(IOException::class)
    fun check(): UpdateInfo? {
        val conn = open(LATEST_RELEASE).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        try {
            val status = conn.responseCode
            if (status == 404) return null // jeszcze brak wydań
            if (status !in 200..299) throw IOException("GitHub: $status")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name").removePrefix("v") // np. 0.1.7
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            if (code <= BuildConfig.VERSION_CODE) return null
            val assets = json.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?: return null
            return UpdateInfo(
                versionName = tag,
                versionCode = code,
                downloadUrl = apk.getString("browser_download_url"),
                sizeBytes = apk.optLong("size"),
            )
        } finally {
            conn.disconnect()
        }
    }

    @Throws(IOException::class)
    fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "launcher-update.apk")
        file.delete()
        val conn = open(info.downloadUrl).apply { readTimeout = 60_000 }
        try {
            if (conn.responseCode !in 200..299) throw IOException("Pobieranie: ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
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
        return file
    }

    /** Jednorazowa zgoda Androida na instalowanie aplikacji z tego launchera. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Może rzucić ActivityNotFoundException – część telewizorów nie ma tego ekranu. */
    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun launchInstaller(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MiTVLauncher-Android")
        }

    companion object {
        const val REPO = "CjxPL/mitv-launcher"
        private const val LATEST_RELEASE = "https://api.github.com/repos/$REPO/releases/latest"
    }
}
