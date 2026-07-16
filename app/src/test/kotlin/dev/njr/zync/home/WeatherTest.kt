package dev.njr.zync.home

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Open-Meteo parsing + WMO condition mapping (hero weather line). */
class WeatherTest {
    @Test
    fun parsesCurrentConditions() = runBlocking {
        val http = HttpClient(MockEngine { _ ->
            respond(
                """{"current":{"temperature_2m":17.6,"weather_code":2}}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        })
        assertEquals("17° Partly cloudy", OpenMeteo(http).current(52.0, 13.4).toString())
    }

    @Test
    fun failuresDegradeToNullNeverThrow() = runBlocking {
        val http = HttpClient(MockEngine { _ -> respond("nope", HttpStatusCode.InternalServerError) })
        assertNull(OpenMeteo(http).current(0.0, 0.0))
        val garbage = HttpClient(MockEngine { _ -> respond("not json", HttpStatusCode.OK) })
        assertNull(OpenMeteo(garbage).current(0.0, 0.0))
    }

    @Test
    fun wmoCodesMapToShortWords() {
        assertEquals("Clear", OpenMeteo.wmoCondition(0))
        assertEquals("Rain", OpenMeteo.wmoCondition(63))
        assertEquals("Snow", OpenMeteo.wmoCondition(73))
        assertEquals("Thunderstorm", OpenMeteo.wmoCondition(95))
        assertEquals("—", OpenMeteo.wmoCondition(42))
    }
}
