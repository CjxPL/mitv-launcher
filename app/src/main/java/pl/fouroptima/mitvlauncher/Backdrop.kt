package pl.fouroptima.mitvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.random.Random

/**
 * Tapeta w tle: losowe zdjęcie dnia z archiwum Bing (bez klucza, kilkanaście krajów × 8 dni),
 * zapisywane na sticku. Gdy nie ma internetu – zdjęcie z pakietu wg pory dnia, a bez niego gradient.
 * Dekodowanie do ok. 960 px szerokości, żeby nie zjadać pamięci (stick ma 1 GB).
 */
class Backdrop(private val context: Context) {

    private val dir = File(context.cacheDir, "backdrop").apply { mkdirs() }

    /** Ostatnio pobrana tapeta z dysku (szybko, bez sieci). */
    fun cached(): Bitmap? = File(dir, "current.jpg").takeIf { it.length() > 10_000 }?.let { decode(it) }

    /** Zdjęcie z pakietu wg pory dnia. */
    fun bundled(): Bitmap? {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val name = when (hour) {
            in 5..8 -> "morning"
            in 9..16 -> "day"
            in 17..20 -> "evening"
            else -> "night"
        }
        return runCatching {
            context.assets.open("photos/$name.jpg").use { input ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 1; inPreferredConfig = Bitmap.Config.RGB_565 }
                BitmapFactory.decodeStream(input, null, opts)
            }
        }.getOrNull()
    }

    /** Pobiera nową losową tapetę. Blokujące – poza wątkiem głównym. */
    @Throws(IOException::class)
    fun fetchRandom(): Bitmap {
        val market = MARKETS[Random.nextInt(MARKETS.size)]
        val idx = Random.nextInt(8)
        val meta = JSONObject(get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=$idx&n=1&mkt=$market"))
        val image = meta.getJSONArray("images").getJSONObject(0)
        val base = image.getString("urlbase") // np. /th?id=OHR.Nazwa
        val url = "https://www.bing.com$base" + "_1920x1080.jpg"
        val tmp = File(dir, "download.jpg")
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw IOException("Tapeta: ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        val bmp = decode(tmp) ?: throw IOException("Nie udało się odczytać tapety")
        tmp.renameTo(File(dir, "current.jpg"))
        return bmp
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 960) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    private fun get(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MiTVLauncher-Android")
        }

    private companion object {
        val MARKETS = listOf("pl-PL", "en-US", "en-GB", "de-DE", "fr-FR", "ja-JP", "en-AU", "en-CA", "es-ES", "it-IT", "en-IN", "pt-BR")
    }
}
