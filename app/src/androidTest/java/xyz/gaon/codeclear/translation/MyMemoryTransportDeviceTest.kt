package xyz.gaon.codeclear.translation

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyMemoryTransportDeviceTest {
    @Test fun parserRejectsMalformedFailuresQuotasAndOversizedOutput() {
        assertEquals(MyMemoryReply.Failed, parseMyMemoryResponse("not JSON"))
        assertEquals(MyMemoryReply.Failed, parseMyMemoryResponse("{\"responseStatus\":200}"))
        assertEquals(
            MyMemoryReply.Quota,
            parseMyMemoryResponse("{\"responseStatus\":200,\"quotaFinished\":true}")
        )
        assertEquals(
            MyMemoryReply.Failed,
            parseMyMemoryResponse(
                "{\"responseStatus\":403,\"responseData\":{\"translatedText\":\"failed\"}}"
            )
        )
        assertEquals(MyMemoryReply.Failed, parseMyMemoryResponse(success("x".repeat(16385))))
        assertEquals(
            MyMemoryReply.Text("Safe & <b>plain</b>"),
            parseMyMemoryResponse(success("Safe &amp; &lt;b&gt;plain&lt;/b&gt;"))
        )
    }

    @Test fun requestsUseTheFixedHttpsEndpointAndOneParagraphWithoutKeysOrContributions() {
        val calls = AtomicInteger()
        val transport = HttpsMyMemoryTransport(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                calls.incrementAndGet()
                assertEquals("https", request.url.scheme)
                assertEquals("api.mymemory.translated.net", request.url.host)
                assertEquals("/get", request.url.encodedPath)
                assertEquals("[DRILL] Synthetic", request.url.queryParameter("q"))
                assertEquals("en|ja", request.url.queryParameter("langpair"))
                assertEquals(setOf("q", "langpair"), request.url.queryParameterNames)
                assertEquals("GET", request.method)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(success("Synthetic result").toResponseBody()).build()
            }
        )
        assertEquals(
            MyMemoryReply.Text("Synthetic result"),
            transport.translate("[DRILL] Synthetic", "en", "ja", RemoteCancellation())
        )
        assertEquals(1, calls.get())
    }

    @Test fun redirectsOversizedResponsesAndNetworkFailuresAreNeverRetried() {
        for (mode in listOf("redirect", "large", "failure", "quota")) {
            val calls = AtomicInteger()
            val transport = HttpsMyMemoryTransport(
                OkHttpClient.Builder().addInterceptor { chain ->
                    calls.incrementAndGet()
                    if (mode == "failure") throw IOException("Synthetic failure")
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(
                            if (mode == "redirect") {
                                302
                            } else if (mode == "quota") {
                                429
                            } else {
                                200
                            }
                        )
                        .message("Fixture").header("Location", "https://example.invalid/")
                        .body(
                            (
                                if (mode ==
                                    "large"
                                ) {
                                    "x".repeat(65537)
                                } else {
                                    ""
                                }
                                ).toResponseBody()
                        ).build()
                }
            )
            assertEquals(
                if (mode == "quota") MyMemoryReply.Quota else MyMemoryReply.Failed,
                transport.translate("[DRILL] Synthetic", "en", "ja", RemoteCancellation())
            )
            assertEquals(1, calls.get())
        }
    }

    @Test fun preCancelledCallsNeverReachTheClient() {
        val calls = AtomicInteger()
        val transport =
            HttpsMyMemoryTransport(
                OkHttpClient.Builder().addInterceptor {
                    calls.incrementAndGet()
                    error("Cancelled request")
                }
            )
        val cancellation = RemoteCancellation().apply { cancel() }
        assertEquals(
            MyMemoryReply.Failed,
            transport.translate("[DRILL] Synthetic", "en", "ja", cancellation)
        )
        assertEquals(0, calls.get())
        var stopped = false
        val token = RemoteCancellation()
        assertTrue(token.install { stopped = true })
        token.cancel()
        assertTrue(stopped)
        assertFalse(token.install { stopped = true })
    }

    @Test fun statusFollowUpsRetainTheSameSingleTransmissionPermit() {
        val permit = RemoteSendPermit()
        val original = okhttp3.Request.Builder().url("https://api.mymemory.translated.net/get")
            .tag(RemoteSendPermit::class.java, permit).build()
        val followUp = original.newBuilder().build()
        assertTrue(original.tag(RemoteSendPermit::class.java)!!.claim())
        assertFalse(followUp.tag(RemoteSendPermit::class.java)!!.claim())
        assertTrue(RemoteSendPermit().claim())
    }

    @Test fun invalidParagraphsAndMalformedUnicodeAreRejectedBeforeClientAccess() {
        val calls = AtomicInteger()
        val transport =
            HttpsMyMemoryTransport(
                OkHttpClient.Builder().addInterceptor {
                    calls.incrementAndGet()
                    error("Invalid input")
                }
            )
        for (body in listOf(
            "two\nparagraphs",
            "two\u2029paragraphs",
            "bad\u0000text",
            "bad\uD800",
            "x".repeat(501)
        )) {
            var rejected = false
            try {
                transport.translate(body, "en", "ja", RemoteCancellation())
            } catch (
                _: IllegalArgumentException
            ) {
                rejected =
                    true
            }
            assertTrue(rejected)
        }
        assertEquals(0, calls.get())
    }

    private fun success(text: String) = org.json.JSONObject().put("responseStatus", 200)
        .put("responseData", org.json.JSONObject().put("translatedText", text)).toString()
}
