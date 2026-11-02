package xyz.gaon.codeclear.translation

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedServerTransportDeviceTest {
    private val endpoint =
        requireNotNull(OwnedServerEndpoint.parse("https://example.invalid/api.php"))
    private val token =
        requireNotNull(ServerAccessToken.parse("synthetic-client-token-aaaaaaaaaaaaaaaa"))
    private val original = "[DRILL] Gate 3. Call 112."
    private val translation = "[DRILL] Synthetic result 3. Call 112."

    @Test
    fun requestsUseOnlyTheConfiguredHttpsRouteAndAnAuthenticatedJsonPost() {
        val calls = AtomicInteger()
        OwnedServerTransport(
            endpoint,
            OkHttpClient.Builder().addInterceptor { chain ->
                calls.incrementAndGet()
                val request = chain.request()
                assertEquals("https://example.invalid/api.php", request.url.toString())
                assertEquals(null, request.url.query)
                assertEquals("POST", request.method)
                assertEquals(token.authorization(), request.header("Authorization"))
                val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                val json = JSONObject(body)
                assertEquals(setOf("text", "target_lang"), json.keys().asSequence().toSet())
                assertEquals(original, json.getString("text"))
                assertEquals("ko", json.getString("target_lang"))
                response(request, 200, success())
            }
        ).use { transport ->
            assertEquals(
                OwnedServerReply.Text(translation),
                transport.translate(
                    original,
                    TranslationTarget.KOREAN,
                    token,
                    RemoteCancellation()
                ) {
                    true
                }
            )
        }
        assertEquals(1, calls.get())
        assertFalse(token.toString().contains("synthetic-client-token"))
    }

    @Test
    fun strictResponseValidationRejectsWrongCorrelationDuplicatesClaimsAndProtectedTextChanges() {
        assertEquals(OwnedServerReply.Text(translation), parse(success()))
        val malformed = listOf(
            "not JSON",
            success() + " {}",
            success().dropLast(1) + ",\"target_lang\":\"ko\"}",
            JSONObject(success()).put("target_lang", "ja").toString(),
            JSONObject(success()).put("source_sha256", "0".repeat(64)).toString(),
            JSONObject(success()).put("translated_text", "lost numbers").toString(),
            JSONObject(success()).put("translated_text", "x".repeat(16385)).toString(),
            JSONObject(success()).put("translated_text", listOf("wrong type")).toString(),
            JSONObject(success()).put("extra", "unexpected").toString(),
            JSONObject(
                success()
            ).put(
                "issuer",
                JSONObject().put("original", "invented").put("translated", "invented")
            ).toString(),
            JSONObject(
                success()
            ).put(
                "issuer",
                JSONObject().put("original", "").put("translated", "invented")
            ).toString()
        )
        malformed.forEach { assertEquals(OwnedServerReply.Failed, parse(it)) }
        assertNull(OwnedServerEndpoint.parse("http://example.invalid/api.php"))
        assertNull(OwnedServerEndpoint.parse("https://user:secret@example.invalid/api.php"))
        assertNull(OwnedServerEndpoint.parse("https://example.invalid/api.php?key=secret"))
        assertNull(OwnedServerEndpoint.parse("https://127.0.0.1/api.php"))
    }

    @Test
    fun failuresQuotaRedirectsAndOversizedResponsesNeverRetryOrForwardCredentials() {
        for ((code, expected) in listOf(
            302 to OwnedServerReply.Failed,
            401 to OwnedServerReply.Unavailable,
            413 to OwnedServerReply.TooLong,
            429 to OwnedServerReply.Quota,
            503 to OwnedServerReply.Failed
        )) {
            val calls = AtomicInteger()
            OwnedServerTransport(
                endpoint,
                OkHttpClient.Builder().addInterceptor { chain ->
                    calls.incrementAndGet()
                    response(chain.request(), code, "{}").newBuilder()
                        .header("Location", "https://other.invalid/api.php").build()
                }
            ).use { transport ->
                assertEquals(
                    expected,
                    transport.translate(
                        original,
                        TranslationTarget.KOREAN,
                        token,
                        RemoteCancellation()
                    ) {
                        true
                    }
                )
            }
            assertEquals(1, calls.get())
        }
        for (oversized in listOf(false, true)) {
            val calls = AtomicInteger()
            OwnedServerTransport(
                endpoint,
                OkHttpClient.Builder().addInterceptor { chain ->
                    calls.incrementAndGet()
                    if (!oversized) throw IOException("Synthetic transport failure")
                    response(chain.request(), 200, "x".repeat(65537))
                }
            ).use { transport ->
                assertEquals(
                    OwnedServerReply.Failed,
                    transport.translate(
                        original,
                        TranslationTarget.KOREAN,
                        token,
                        RemoteCancellation()
                    ) {
                        true
                    }
                )
            }
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun revokedConsentCancellationAndOversizedInputBlockTransmissionAndLateOutput() {
        val allowed = AtomicBoolean(false)
        val calls = AtomicInteger()
        OwnedServerTransport(
            endpoint,
            OkHttpClient.Builder().addInterceptor { chain ->
                calls.incrementAndGet()
                allowed.set(false)
                response(chain.request(), 200, success())
            }
        ).use { transport ->
            assertEquals(
                OwnedServerReply.Unavailable,
                transport.translate(
                    original,
                    TranslationTarget.KOREAN,
                    token,
                    RemoteCancellation(),
                    allowed::get
                )
            )
            assertEquals(0, calls.get())
            allowed.set(true)
            assertEquals(
                OwnedServerReply.TooLong,
                transport.translate(
                    "가".repeat(5001),
                    TranslationTarget.KOREAN,
                    token,
                    RemoteCancellation(),
                    allowed::get
                )
            )
            assertEquals(0, calls.get())
            val cancelled = RemoteCancellation().apply { cancel() }
            assertEquals(
                OwnedServerReply.Unavailable,
                transport.translate(
                    original,
                    TranslationTarget.KOREAN,
                    token,
                    cancelled,
                    allowed::get
                )
            )
            assertEquals(0, calls.get())
            assertEquals(
                OwnedServerReply.Unavailable,
                transport.translate(
                    original,
                    TranslationTarget.KOREAN,
                    token,
                    RemoteCancellation(),
                    allowed::get
                )
            )
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun cacheClearingIsAnExplicitAuthenticatedBodylessDelete() {
        val calls = AtomicInteger()
        OwnedServerTransport(
            endpoint,
            OkHttpClient.Builder().addInterceptor { chain ->
                calls.incrementAndGet()
                val request = chain.request()
                assertEquals("DELETE", request.method)
                assertTrue(request.body == null || request.body!!.contentLength() == 0L)
                assertEquals(token.authorization(), request.header("Authorization"))
                response(request, 200, "{\"cleared\":true}")
            }
        ).use { transport ->
            assertEquals(
                ServerCacheClearResult.UNAVAILABLE,
                transport.clearCache(token, RemoteCancellation()) {
                    false
                }
            )
            assertEquals(0, calls.get())
            assertEquals(
                ServerCacheClearResult.CLEARED,
                transport.clearCache(token, RemoteCancellation()) {
                    true
                }
            )
            assertEquals(1, calls.get())
        }
    }

    private fun parse(json: String) = parseOwnedServerResponse(json, original, "ko")

    private fun success(): String = JSONObject().put("translated_text", translation)
        .put("issuer", JSONObject().put("original", "").put("translated", ""))
        .put("target_lang", "ko").put("source_sha256", serverDigest(original)).toString()

    private fun response(request: okhttp3.Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
}
