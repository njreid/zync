package dev.njr.zync.home

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The hero line's weather: current temperature + a short condition word. */
data class WeatherNow(val temperatureC: Int, val condition: String) {
    override fun toString(): String = "$temperatureC° $condition"
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
            WeatherNow(
                temperatureC = current["temperature_2m"]!!.jsonPrimitive.content.toDouble().toInt(),
                condition = wmoCondition(current["weather_code"]!!.jsonPrimitive.content.toInt()),
            )
        }.getOrNull()
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
    }
}
