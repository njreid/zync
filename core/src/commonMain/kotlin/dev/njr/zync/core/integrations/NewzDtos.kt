package dev.njr.zync.core.integrations

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `POST /integrations/newz/handoff` response: where the WebView should navigate. */
@Serializable
data class NewzHandoffResponse(
    @SerialName("handoff_url") val handoffUrl: String,
    /** ISO-8601; the token inside the URL is single-use and expires within 60s. */
    @SerialName("expires_at") val expiresAt: String,
)

/** `POST /integrations/newz/redeem` body (newz → zync, service-credential auth). */
@Serializable
data class NewzRedeemRequest(val jti: String)

/** Successful redemption: the device this handoff authenticates. */
@Serializable
data class NewzRedeemResponse(@SerialName("device_id") val deviceId: String)
