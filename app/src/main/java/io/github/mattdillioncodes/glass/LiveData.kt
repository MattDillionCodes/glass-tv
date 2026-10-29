package io.github.mattdillioncodes.glass

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Network work stays off Glass.worker so a slow request never delays a wallpaper change. */
object Net {
    val io = Executors.newSingleThreadExecutor()

    fun json(url: String): JSONObject = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("User-Agent", "Glass-TV-Launcher/1.0")
        try { JSONObject(inputStream.use { String(it.readBytes()) }) } finally { disconnect() }
    }
}

/** Current conditions from Open-Meteo (free, no API key, no account). */
class Weather(val temp: Int, val code: Int, val isDay: Boolean) {
    /** WMO weather code -> emoji. */
    val symbol: String
        get() = when (code) {
            0 -> if (isDay) "☀️" else "🌙"
            1, 2 -> if (isDay) "🌤️" else "☁️"
            3 -> "☁️"
            45, 48 -> "🌫️"
            in 51..57 -> "🌦️"
            in 61..67, in 80..82 -> "🌧️"
            in 71..77, 85, 86 -> "🌨️"
            in 95..99 -> "⛈️"
            else -> "🌡️"
        }

    companion object {
        fun fetch(lat: Double, lon: Double, fahrenheit: Boolean): Weather {
            val unit = if (fahrenheit) "&temperature_unit=fahrenheit" else ""
            val now = Net.json("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,is_day$unit&timezone=auto").getJSONObject("current")
            return Weather(now.getDouble("temperature_2m").roundToInt(), now.getInt("weather_code"), now.getInt("is_day") == 1)
        }
    }
}

/** A place from Open-Meteo's geocoding search, for picking the weather city on screen. */
class Place(val name: String, val lat: Double, val lon: Double)

object Places {
    fun search(query: String): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val lang = Locale.getDefault().language
        val results = Net.json("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=6&language=$lang")
            .optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val label = listOf(r.optString("name"), r.optString("admin1"), r.optString("country"))
                .filter { it.isNotBlank() }.distinct().joinToString(", ")
            Place(label, r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }
}
