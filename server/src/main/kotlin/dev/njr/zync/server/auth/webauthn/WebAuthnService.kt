package dev.njr.zync.server.auth.webauthn

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.authenticator.AuthenticatorImpl
import com.webauthn4j.converter.AttestationObjectConverter
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Verifies WebAuthn registration and assertion ceremonies (webauthn4j) for the single
 * browser user, and issues the option payloads. Attestation is not required (self-hosted,
 * single user) — a `none`-attestation passkey is accepted; the security guarantees that
 * matter here are challenge freshness (see [ChallengeStore]), origin/rpId binding, and the
 * signature + monotonic sign-counter check that webauthn4j performs.
 */
class WebAuthnService(
    private val config: WebAuthnConfig,
    private val credentials: WebauthnCredentialStore,
    private val challenges: ChallengeStore,
    private val randomChallenge: () -> ByteArray = { ByteArray(32).also { SecureRandom().nextBytes(it) } },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val objectConverter = ObjectConverter()
    private val manager: WebAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter)
    private val attestedConverter = AttestedCredentialDataConverter(objectConverter)
    private val attestationObjectConverter = AttestationObjectConverter(objectConverter)
    private val origin = Origin(config.origin)

    fun hasCredentials(): Boolean = credentials.count() > 0

    fun registrationOptions(): RegisterOptions {
        val challenge = b64UrlEncode(randomChallenge())
        challenges.issue(challenge)
        return RegisterOptions(
            challenge = challenge,
            rp = RpDto(config.rpId, config.rpName),
            user = UserDto(b64UrlEncode(config.userHandle), config.userName, config.userDisplayName),
            pubKeyCredParams = listOf(CredParam(alg = -7), CredParam(alg = -257)),
            timeout = 60_000L,
        )
    }

    /** Verify a registration response and persist the credential. Returns true on success. */
    fun verifyRegistration(resp: RegisterResponse): Boolean {
        val challenge = clientChallenge(resp.response.clientDataJSON) ?: return false
        if (!challenges.consume(challenge)) return false
        val attestationObject = b64UrlDecode(resp.response.attestationObject)
        val clientDataJSON = b64UrlDecode(resp.response.clientDataJSON)
        val serverProperty = ServerProperty(origin, config.rpId, DefaultChallenge(b64UrlDecode(challenge)))
        val params = RegistrationParameters(serverProperty, null, false, true)
        try {
            manager.verify(RegistrationRequest(attestationObject, clientDataJSON), params)
        } catch (_: Exception) {
            return false
        }
        val authData = attestationObjectConverter.convert(attestationObject)?.authenticatorData ?: return false
        val acd = authData.attestedCredentialData ?: return false
        credentials.save(
            WebauthnCredentialStore.Record(
                credentialId = b64UrlEncode(acd.credentialId),
                userHandle = b64UrlEncode(config.userHandle),
                attestedCredentialData = b64Encode(attestedConverter.convert(acd)),
                signCount = authData.signCount,
            ),
            createdAt = now(),
        )
        return true
    }

    fun assertionOptions(): AssertOptions {
        val challenge = b64UrlEncode(randomChallenge())
        challenges.issue(challenge)
        return AssertOptions(
            challenge = challenge,
            rpId = config.rpId,
            timeout = 60_000L,
            allowCredentials = credentials.all().map { CredDescriptor(id = it.credentialId) },
        )
    }

    /** Verify an assertion; on success bump the sign counter and return true. */
    fun verifyAssertion(resp: AssertResponse): Boolean {
        val challenge = clientChallenge(resp.response.clientDataJSON) ?: return false
        if (!challenges.consume(challenge)) return false
        val stored = credentials.get(resp.rawId) ?: return false
        val acd = attestedConverter.convert(b64Decode(stored.attestedCredentialData))
        val credentialRecord = AuthenticatorImpl(acd, NoneAttestationStatement(), stored.signCount)
        val serverProperty = ServerProperty(origin, config.rpId, DefaultChallenge(b64UrlDecode(challenge)))
        val authRequest = AuthenticationRequest(
            b64UrlDecode(resp.rawId),
            b64UrlDecode(resp.response.authenticatorData),
            b64UrlDecode(resp.response.clientDataJSON),
            b64UrlDecode(resp.response.signature),
        )
        val params = AuthenticationParameters(serverProperty, credentialRecord, null, false)
        val authData = try {
            manager.verify(authRequest, params)
        } catch (_: Exception) {
            return false
        }
        authData.authenticatorData?.signCount?.let { credentials.updateSignCount(resp.rawId, it) }
        return true
    }

    /** Pull the base64url `challenge` field out of a base64url-encoded clientDataJSON. */
    private fun clientChallenge(clientDataJSONb64: String): String? = try {
        Json.parseToJsonElement(b64UrlDecode(clientDataJSONb64).decodeToString())
            .jsonObject["challenge"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }
}
