package pl.fouroptima.mitvlauncher

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class WeatherInfo(
    val temp: Int,
    val max: Int,
    val min: Int,
    val code: Int,
    val isDay: Boolean,
    val city: String,
    val fetchedAt: Long,
) {
    val isStale get() = System.currentTimeMillis() - fetchedAt > 30 * 60 * 1000L

    /** Ikona wg kodu pogody WMO (tak raportuje Open-Meteo). */
    val iconRes: Int
        get() = when (code) {
            0 -> if (isDay) R.drawable.ic_w_sun else R.drawable.ic_w_moon
            1, 2 -> if (isDay) R.drawable.ic_w_partly else R.drawable.ic_w_partly_night
            3 -> R.drawable.ic_w_cloud
            45, 48 -> R.drawable.ic_w_fog
            in 51..67, in 80..82 -> R.drawable.ic_w_rain
            in 71..77, 85, 86 -> R.drawable.ic_w_snow
            in 95..99 -> R.drawable.ic_w_storm
            else -> R.drawable.ic_w_cloud
        }

    val descriptionRes: Int
        get() = when (code) {
            0 -> if (isDay) R.string.w_clear else R.string.w_clear_night
            1 -> R.string.w_mostly_clear
            2 -> R.string.w_partly
            3 -> R.string.w_overcast
            45, 48 -> R.string.w_fog
            in 51..57 -> R.string.w_drizzle
            in 61..67 -> R.string.w_rain
            in 71..77 -> R.string.w_snow
            80, 81, 82 -> R.string.w_showers
            85, 86 -> R.string.w_snow_showers
            in 95..99 -> R.string.w_storm
            else -> R.string.w_overcast
        }
}

/**
 * Pogoda z Open-Meteo (bez klucza, bez konta). Miejsce ustalane raz na dobę po adresie internetowym
 * (ipapi.co); gdy się nie uda – Warszawa. Metody sieciowe są blokujące – wywoływać poza wątkiem głównym.
 */
class Weather(context: Context) {

    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)

    fun cached(): WeatherInfo? {
        if (!prefs.contains("temp")) return null
        return WeatherInfo(
            temp = prefs.getInt("temp", 0),
            max = prefs.getInt("max", 0),
            min = prefs.getInt("min", 0),
            code = prefs.getInt("code", 3),
            isDay = prefs.getBoolean("day", true),
            city = prefs.getString("city", "").orEmpty(),
            fetchedAt = prefs.getLong("at", 0L),
        )
    }

    @Throws(IOException::class)
    fun fetch(): WeatherInfo {
        val (lat, lon, city) = location()
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=1"
        val json = JSONObject(get(url))
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val info = WeatherInfo(
            temp = current.getDouble("temperature_2m").roundToInt(),
            max = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            min = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            code = current.getInt("weather_code"),
            isDay = current.getInt("is_day") == 1,
            city = city,
            fetchedAt = System.currentTimeMillis(),
        )
        prefs.edit()
            .putInt("temp", info.temp).putInt("max", info.max).putInt("min", info.min)
            .putInt("code", info.code).putBoolean("day", info.isDay)
            .putString("city", info.city).putLong("at", info.fetchedAt)
            .apply()
        return info
    }

    /** Współrzędne i miasto; zapamiętane na dobę, żeby nie pytać przy każdym odświeżeniu. */
    private fun location(): Triple<Double, Double, String> {
        val at = prefs.getLong("loc_at", 0L)
        if (prefs.contains("lat") && System.currentTimeMillis() - at < 24 * 60 * 60 * 1000L) {
            return Triple(
                prefs.getString("lat", null)!!.toDouble(),
                prefs.getString("lon", null)!!.toDouble(),
                prefs.getString("loc_city", "").orEmpty(),
            )
        }
        val located = runCatching {
            val j = JSONObject(get("https://ipapi.co/json/"))
            Triple(j.getDouble("latitude"), j.getDouble("longitude"), j.optString("city", ""))
        }.getOrNull()
        val result = located ?: Triple(52.23, 21.01, "Warszawa")
        if (located != null) {
            prefs.edit()
                .putString("lat", result.first.toString()).putString("lon", result.second.toString())
                .putString("loc_city", result.third).putLong("loc_at", System.currentTimeMillis())
                .apply()
        }
        return result
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "MiTVLauncher-Android")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
