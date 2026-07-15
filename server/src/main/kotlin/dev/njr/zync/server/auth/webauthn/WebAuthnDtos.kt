package dev.njr.zync.server.auth.webauthn

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

// --- Options the server issues (serialized to JSON for navigator.credentials) ---
//
// NOTE: these are a BROWSER contract — navigator.credentials requires members like
// pubKeyCredParams[].type to be present. The server's ContentNegotiation Json does
// not encode default values, so every defaulted property here must be @EncodeDefault
// or Chrome fails with "Required member is undefined".

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RegisterOptions(
    val challenge: String,               // base64url
    val rp: RpDto,
    val user: UserDto,
    val pubKeyCredParams: List<CredParam>,
    val timeout: Long,
    @EncodeDefault val attestation: String = "none",
    @EncodeDefault val authenticatorSelection: AuthSelection = AuthSelection(),
)

@Serializable data class RpDto(val id: String, val name: String)
@Serializable data class UserDto(val id: String, val name: String, val displayName: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class CredParam(val alg: Int, @EncodeDefault val type: String = "public-key")

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class AuthSelection(
    @EncodeDefault val userVerification: String = "preferred",
    @EncodeDefault val residentKey: String = "preferred",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AssertOptions(
    val challenge: String,               // base64url
    val rpId: String,
    val timeout: Long,
    val allowCredentials: List<CredDescriptor>,
    @EncodeDefault val userVerification: String = "preferred",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class CredDescriptor(val id: String, @EncodeDefault val type: String = "public-key")

// --- Responses the browser sends back (navigator.credentials results, base64url'd) ---

@Serializable
data class RegisterResponse(val id: String, val rawId: String, val type: String, val response: RegAttestation)

@Serializable
data class RegAttestation(val clientDataJSON: String, val attestationObject: String)

@Serializable
data class AssertResponse(val id: String, val rawId: String, val type: String, val response: AssertData)

@Serializable
data class AssertData(
    val clientDataJSON: String,
    val authenticatorData: String,
    val signature: String,
    val userHandle: String? = null,
)

/** The opaque bearer token minted for a browser after a verified assertion. */
@Serializable
data class SessionResponse(val token: String)

// --- base64 helpers (WebAuthn wire format is base64url, no padding) ---

@OptIn(ExperimentalEncodingApi::class)
private val B64URL = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

@OptIn(ExperimentalEncodingApi::class)
fun b64UrlEncode(bytes: ByteArray): String = B64URL.encode(bytes).trimEnd('=')

@OptIn(ExperimentalEncodingApi::class)
fun b64UrlDecode(s: String): ByteArray = B64URL.decode(s)

@OptIn(ExperimentalEncodingApi::class)
fun b64Encode(bytes: ByteArray): String = Base64.encode(bytes)

@OptIn(ExperimentalEncodingApi::class)
fun b64Decode(s: String): ByteArray = Base64.decode(s)
