package dev.njr.zync.server.auth.webauthn

import com.webauthn4j.data.AttestationConveyancePreference
import com.webauthn4j.data.AuthenticatorAssertionResponse
import com.webauthn4j.data.AuthenticatorAttestationResponse
import com.webauthn4j.data.AuthenticatorSelectionCriteria
import com.webauthn4j.data.PublicKeyCredentialCreationOptions
import com.webauthn4j.data.PublicKeyCredentialDescriptor
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialRequestOptions
import com.webauthn4j.data.PublicKeyCredentialRpEntity
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.PublicKeyCredentialUserEntity
import com.webauthn4j.data.UserVerificationRequirement
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.test.EmulatorUtil
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor
import com.webauthn4j.test.client.ClientPlatform
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.data.db.ZyncDatabase
import dev.njr.zync.server.auth.Authenticator
import dev.njr.zync.server.auth.ServerAuth
import dev.njr.zync.server.auth.SessionStore
import dev.njr.zync.server.content.ServerContent
import dev.njr.zync.server.sync.SyncService
import dev.njr.zync.server.zyncModule
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end WebAuthn: a webauthn4j emulator authenticator plays the browser, running real
 * registration and assertion ceremonies against the server routes. Proves the server issues
 * valid options, verifies the signed responses, mints a session, and gates the `:web` UI.
 */
class WebAuthnTest {
    private val json = Json
    private val rpId = "localhost"
    private val origin = "http://localhost"

    private fun config() = WebAuthnConfig(
        rpId = rpId, rpName = "zync", origin = origin,
        userHandle = "zync-admin".encodeToByteArray(), userName = "zync", userDisplayName = "zync",
    )

    private fun ApplicationTestBuilder.wire(db: ZyncDatabase, sessions: SessionStore, regToken: String?): WebAuthnEndpoint {
        val service = SyncService(db)
        val content = ServerContent(service)
        val endpoint = WebAuthnEndpoint(
            WebAuthnService(config(), WebauthnCredentialStore(db), ChallengeStore()), sessions, regToken,
        )
        application {
            zyncModule(service, auth = ServerAuth(Authenticator.AllowAll, sessions), content = content, webauthn = endpoint)
        }
        return endpoint
    }

    // NONE attestation: no X.509 cert generation (which would hit a BouncyCastle-version
    // mismatch in webauthn4j-test's cert builder). Our server accepts none-attestation passkeys.
    private fun newClientPlatform() =
        ClientPlatform(Origin(origin), WebAuthnAuthenticatorAdaptor(EmulatorUtil.NONE_ATTESTATION_AUTHENTICATOR))

    @Test
    fun registerThenAssertMintsASessionThatUnlocksTheWebUi() = testApplication {
        val db = JvmZyncDatabase.inMemory()
        val sessions = SessionStore()
        wire(db, sessions, regToken = "reg-secret")
        val platform = newClientPlatform()

        // --- the :web UI is gated before any session ---
        assertEquals(HttpStatusCode.Unauthorized, client.get("/tree").status)

        // --- registration ---
        val regOpts = json.decodeFromString(
            RegisterOptions.serializer(),
            client.get("/auth/webauthn/register/options") { header("X-Registration-Token", "reg-secret") }.bodyAsText(),
        )
        val creationOptions = PublicKeyCredentialCreationOptions(
            PublicKeyCredentialRpEntity(regOpts.rp.id, regOpts.rp.name),
            PublicKeyCredentialUserEntity(b64UrlDecode(regOpts.user.id), regOpts.user.name, regOpts.user.displayName),
            DefaultChallenge(b64UrlDecode(regOpts.challenge)),
            listOf(PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
            60_000L,
            emptyList(),
            AuthenticatorSelectionCriteria(null, false, UserVerificationRequirement.DISCOURAGED),
            AttestationConveyancePreference.NONE,
            null,
        )
        val made = platform.create(creationOptions)
        val attResp = made.response as AuthenticatorAttestationResponse
        val registerBody = RegisterResponse(
            id = b64UrlEncode(made.rawId), rawId = b64UrlEncode(made.rawId), type = "public-key",
            response = RegAttestation(
                clientDataJSON = b64UrlEncode(attResp.clientDataJSON),
                attestationObject = b64UrlEncode(attResp.attestationObject),
            ),
        )
        val regResult = client.post("/auth/webauthn/register") {
            header("X-Registration-Token", "reg-secret")
            header(HttpHeaders.ContentType, "application/json")
            setBody(json.encodeToString(RegisterResponse.serializer(), registerBody))
        }
        assertEquals(HttpStatusCode.OK, regResult.status)

        // --- assertion ---
        val assertOpts = json.decodeFromString(
            AssertOptions.serializer(), client.get("/auth/webauthn/assert/options").bodyAsText(),
        )
        assertTrue(assertOpts.allowCredentials.isNotEmpty(), "the registered credential should be offered")
        val requestOptions = PublicKeyCredentialRequestOptions(
            DefaultChallenge(b64UrlDecode(assertOpts.challenge)),
            assertOpts.timeout,
            assertOpts.rpId,
            assertOpts.allowCredentials.map {
                PublicKeyCredentialDescriptor(PublicKeyCredentialType.PUBLIC_KEY, b64UrlDecode(it.id), null)
            },
            UserVerificationRequirement.PREFERRED,
            null,
        )
        val got = platform.get(requestOptions)
        val asResp = got.response as AuthenticatorAssertionResponse
        val assertBody = AssertResponse(
            id = b64UrlEncode(got.rawId), rawId = b64UrlEncode(got.rawId), type = "public-key",
            response = AssertData(
                clientDataJSON = b64UrlEncode(asResp.clientDataJSON),
                authenticatorData = b64UrlEncode(asResp.authenticatorData),
                signature = b64UrlEncode(asResp.signature),
                userHandle = asResp.userHandle?.let { b64UrlEncode(it) },
            ),
        )
        val token = json.decodeFromString(
            SessionResponse.serializer(),
            client.post("/auth/webauthn/assert") {
                header(HttpHeaders.ContentType, "application/json")
                setBody(json.encodeToString(AssertResponse.serializer(), assertBody))
            }.also { assertEquals(HttpStatusCode.OK, it.status) }.bodyAsText(),
        ).token

        // --- the minted session now unlocks the gated :web UI ---
        val unlocked = client.get("/tree") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, unlocked.status)
    }

    @Test
    fun `the web gate cannot be bypassed with a spoofed device header`() = testApplication {
        wire(JvmZyncDatabase.inMemory(), SessionStore(), regToken = "reg-secret")
        // A client-supplied X-Device-Id must NOT skip the browser session gate (regression: it did).
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/tree") { header("X-Device-Id", "anything") }.status,
        )
    }

    @Test
    fun registrationRequiresTheRegistrationToken() = testApplication {
        wire(JvmZyncDatabase.inMemory(), SessionStore(), regToken = "reg-secret")
        assertEquals(HttpStatusCode.Forbidden, client.get("/auth/webauthn/register/options").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/auth/webauthn/register/options") { header("X-Registration-Token", "wrong") }.status,
        )
    }

    @Test
    fun optionsJsonCarriesEveryBrowserRequiredMember() = testApplication {
        // navigator.credentials rejects options with absent members; kotlinx omits
        // defaulted properties unless @EncodeDefault (regression: Chrome failed with
        // "pubKeyCredParams ... 'type' ... Required member is undefined").
        wire(JvmZyncDatabase.inMemory(), SessionStore(), regToken = "reg-secret")
        val reg = client.get("/auth/webauthn/register/options") { header("X-Registration-Token", "reg-secret") }.bodyAsText()
        for (member in listOf("\"type\":\"public-key\"", "\"attestation\"", "\"authenticatorSelection\"", "\"userVerification\"", "\"residentKey\"")) {
            assertTrue(member in reg, "register options must serialize $member — got $reg")
        }
        val assertOpts = client.get("/auth/webauthn/assert/options").bodyAsText()
        assertTrue("\"userVerification\"" in assertOpts, "assert options must serialize userVerification — got $assertOpts")
    }

    @Test
    fun assertionWithoutARegisteredCredentialFails() = testApplication {
        wire(JvmZyncDatabase.inMemory(), SessionStore(), regToken = "reg-secret")
        val platform = newClientPlatform()
        val assertOpts = json.decodeFromString(
            AssertOptions.serializer(), client.get("/auth/webauthn/assert/options").bodyAsText(),
        )
        // No credential registered → the client can't produce an assertion; forge an empty one.
        val body = AssertResponse(
            id = "x", rawId = b64UrlEncode("x".encodeToByteArray()), type = "public-key",
            response = AssertData(
                clientDataJSON = b64UrlEncode("{\"type\":\"webauthn.get\",\"challenge\":\"${assertOpts.challenge}\",\"origin\":\"$origin\"}".encodeToByteArray()),
                authenticatorData = b64UrlEncode(ByteArray(37)),
                signature = b64UrlEncode(ByteArray(8)),
            ),
        )
        val res = client.post("/auth/webauthn/assert") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(json.encodeToString(AssertResponse.serializer(), body))
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}
