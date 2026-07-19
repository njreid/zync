package dev.njr.zync.home

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The hero line's weather: summary icon + temperature + a short condition word. */
data class WeatherNow(val temperatureC: Int, val condition: String, val icon: String) {
    override fun toString(): String = "$icon $temperatureC° $condition"
}

/** One day of forecast for the agenda look-ahead ("Sat 25  🌧 18°"). */
data class DayForecast(val dateIso: String, val icon: String, val maxC: Int) {
    val label: String get() = "$icon $maxC°"
}

/**
 * Open-Meteo current conditions (spec decision: free, keyless; Pixel has no public
 * on-device weather API). Coarse location in, one small JSON out; callers cache.
 */
class OpenMeteo(private val http: HttpClient, private val baseUrl: String = "https://api.open-meteo.com") {
    suspend fun current(latitude: Double, longitude: Double): WeatherNow? {
        val response = runCatching {
            http.get("$baseUrl/v1/forecast?latitude=$latitude&longitude=$longitude&current=temperature_2m,weather_code")
        }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        return runCatching {
            val current = Json.parseToJsonElement(response.bodyAsText()).jsonObject["current"]!!.jsonObject
            val code = current["weather_code"]!!.jsonPrimitive.content.toInt()
            WeatherNow(
                temperatureC = current["temperature_2m"]!!.jsonPrimitive.content.toDouble().toInt(),
                condition = wmoCondition(code),
                icon = wmoIcon(code),
            )
        }.getOrNull()
    }

    /** Seven-day daily forecast (device local dates via timezone=auto); empty on failure. */
    suspend fun daily(latitude: Double, longitude: Double): List<DayForecast> {
        val response = runCatching {
            http.get(
                "$baseUrl/v1/forecast?latitude=$latitude&longitude=$longitude" +
                    "&daily=weather_code,temperature_2m_max&forecast_days=7&timezone=auto",
            )
        }.getOrNull() ?: return emptyList()
        if (!response.status.isSuccess()) return emptyList()
        return runCatching {
            val daily = Json.parseToJsonElement(response.bodyAsText()).jsonObject["daily"]!!.jsonObject
            val dates = daily["time"]!!.jsonArray
            val codes = daily["weather_code"]!!.jsonArray
            val maxes = daily["temperature_2m_max"]!!.jsonArray
            dates.indices.map { i ->
                DayForecast(
                    dateIso = dates[i].jsonPrimitive.content,
                    icon = wmoIcon(codes[i].jsonPrimitive.content.toInt()),
                    maxC = maxes[i].jsonPrimitive.content.toDouble().toInt(),
                )
            }
        }.getOrNull() ?: emptyList()
    }

    companion object {
        /** WMO weather interpretation codes → one short word for the hero line. */
        fun wmoCondition(code: Int): String = when (code) {
            0 -> "Clear"
            1, 2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            in 51..57 -> "Drizzle"
            in 61..67, in 80..82 -> "Rain"
            in 71..77, 85, 86 -> "Snow"
            95, 96, 99 -> "Thunderstorm"
            else -> "—"
        }

        /** WMO code → a summary glyph for the hero line. */
        fun wmoIcon(code: Int): String = when (code) {
            0 -> "☀"
            1, 2 -> "⛅"
            3 -> "☁"
            45, 48 -> "🌫"
            in 51..57 -> "🌦"
            in 61..67, in 80..82 -> "🌧"
            in 71..77, 85, 86 -> "🌨"
            95, 96, 99 -> "⛈"
            else -> "·"
        }
    }
}
