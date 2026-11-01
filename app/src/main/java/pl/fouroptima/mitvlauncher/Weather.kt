package pl.fouroptima.mitvlauncher

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class DayForecast(val epochDay: Long, val code: Int, val max: Int, val min: Int) {
    val iconRes: Int get() = WeatherInfo(0, max, min, code, true, "", 0L).iconRes
}

data class WeatherInfo(
    val temp: Int,
    val max: Int,
    val min: Int,
    val code: Int,
    val isDay: Boolean,
    val city: String,
    val fetchedAt: Long,
    val pressure: Int = 0,
    val daily: List<DayForecast> = emptyList(),
) {
    val isStale get() = System.currentTimeMillis() - fetchedAt > 30 * 60 * 1000L || daily.isEmpty()

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
            pressure = prefs.getInt("pressure", 0),
            daily = parseDaily(prefs.getString("daily", "") ?: ""),
        )
    }

    private fun parseDaily(raw: String): List<DayForecast> = raw.split(';').filter { it.isNotBlank() }.mapNotNull { row ->
        val parts = row.split(',')
        if (parts.size < 4) null else runCatching {
            DayForecast(parts[0].toLong(), parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
        }.getOrNull()
    }

    @Throws(IOException::class)
    fun fetch(): WeatherInfo {
        val (lat, lon, city) = location()
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day,surface_pressure" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=7"
        val json = JSONObject(get(url))
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val times = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val maxes = daily.getJSONArray("temperature_2m_max")
        val mins = daily.getJSONArray("temperature_2m_min")
        val days = (0 until times.length()).map { i ->
            val date = times.getString(i) // RRRR-MM-DD
            val epochDay = java.time.LocalDate.parse(date).toEpochDay()
            DayForecast(epochDay, codes.getInt(i), maxes.getDouble(i).roundToInt(), mins.getDouble(i).roundToInt())
        }
        val info = WeatherInfo(
            temp = current.getDouble("temperature_2m").roundToInt(),
            max = maxes.getDouble(0).roundToInt(),
            min = mins.getDouble(0).roundToInt(),
            code = current.getInt("weather_code"),
            isDay = current.getInt("is_day") == 1,
            city = city,
            fetchedAt = System.currentTimeMillis(),
            pressure = current.optDouble("surface_pressure", 0.0).roundToInt(),
            daily = days,
        )
        prefs.edit()
            .putInt("temp", info.temp).putInt("max", info.max).putInt("min", info.min)
            .putInt("code", info.code).putBoolean("day", info.isDay)
            .putString("city", info.city).putLong("at", info.fetchedAt)
            .putInt("pressure", info.pressure)
            .putString("daily", info.daily.joinToString(";") { "${it.epochDay},${it.code},${it.max},${it.min}" })
            .apply()
        return info
    }

    /** Współrzędne i miasto; zapamiętane na dobę, żeby nie pytać przy każdym odświeżeniu. */
    private fun location(): Triple<Double, Double, String> {
        val at = prefs.getLong("loc_at", 0L)
        val sameVersion = prefs.getInt("loc_ver", 0) == BuildConfig.VERSION_CODE
        if (sameVersion && prefs.contains("lat") && System.currentTimeMillis() - at < 24 * 60 * 60 * 1000L) {
            return Triple(
                prefs.getString("lat", null)!!.toDouble(),
                prefs.getString("lon", null)!!.toDouble(),
                prefs.getString("loc_city", "").orEmpty(),
            )
        }
        val located = runCatching {
            val j = JSONObject(get("https://ipapi.co/json/"))
            Triple(j.getDouble("latitude"), j.getDouble("longitude"), polishCity(j.optString("city", "")))
        }.getOrNull()
        val result = located ?: Triple(52.23, 21.01, "Warszawa")
        if (located != null) {
            prefs.edit()
                .putString("lat", result.first.toString()).putString("lon", result.second.toString())
                .putString("loc_city", result.third).putLong("loc_at", System.currentTimeMillis())
                .putInt("loc_ver", BuildConfig.VERSION_CODE)
                .apply()
        }
        return result
    }

    /** Serwis lokalizacji zwraca nazwy po angielsku – najczęstsze polskie miasta tłumaczymy. */
    private fun polishCity(name: String): String = when (name.trim().lowercase()) {
        "warsaw" -> "Warszawa"
        "cracow", "krakow" -> "Kraków"
        "lodz" -> "Łódź"
        "wroclaw" -> "Wrocław"
        "poznan" -> "Poznań"
        "gdansk" -> "Gdańsk"
        "szczecin" -> "Szczecin"
        "bydgoszcz" -> "Bydgoszcz"
        "lublin" -> "Lublin"
        "bialystok" -> "Białystok"
        "katowice" -> "Katowice"
        "gdynia" -> "Gdynia"
        "czestochowa" -> "Częstochowa"
        "radom" -> "Radom"
        "torun" -> "Toruń"
        "rzeszow" -> "Rzeszów"
        "kielce" -> "Kielce"
        "olsztyn" -> "Olsztyn"
        "lomianki" -> "Łomianki"
        "pruszkow" -> "Pruszków"
        "piaseczno" -> "Piaseczno"
        "legionowo" -> "Legionowo"
        "plock" -> "Płock"
        "wegrow" -> "Węgrów"
        "wolomin" -> "Wołomin"
        "minsk mazowiecki" -> "Mińsk Mazowiecki"
        "siedlce" -> "Siedlce"
        "otwock" -> "Otwock"
        "grodzisk mazowiecki" -> "Grodzisk Mazowiecki"
        "nowy dwor mazowiecki" -> "Nowy Dwór Mazowiecki"
        else -> name
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
