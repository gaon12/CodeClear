package xyz.gaon.codeclear.translation

import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import xyz.gaon.codeclear.BuildConfig
import xyz.gaon.codeclear.sha256Hex
import xyz.gaon.codeclear.toLowerHex

internal class OwnedServerEndpoint private constructor(internal val url: HttpUrl) {
    val scope: String = serverDigest(url.toString())

    companion object {
        fun configured(): OwnedServerEndpoint? = parse(BuildConfig.OWNED_TRANSLATION_URL)

        fun parse(value: String): OwnedServerEndpoint? {
            if (value.length !in 1..2048 || value.any { it.code !in 33..126 }) return null
            if (runCatching { URI(value) }.getOrNull()?.scheme != "https") return null
            val url = value.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() ||
                url.password.isNotEmpty() || url.query != null || url.fragment != null ||
                !url.host.matches(
                    Regex("(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}")
                )
            ) {
                return null
            }
            return OwnedServerEndpoint(url)
        }
    }
}

/** A legacy access token or locally generated installation capability; never a provider key. */
internal class ServerAccessToken private constructor(private val value: String) {
    val fingerprint: String = serverDigest(value)
    internal val installationId: String? get() = value.removePrefix("installation.").takeIf {
        value.startsWith("installation.") && it.matches(Regex("[a-f0-9]{64}"))
    }
    internal fun authorization(): String = "Bearer $value"
    internal fun bytes(): ByteArray = value.toByteArray(Charsets.US_ASCII)
    override fun toString(): String = "ServerAccessToken([redacted])"

    companion object {
        fun newInstallation(): ServerAccessToken = ServerAccessToken(
            "installation." + ByteArray(32).also { SecureRandom().nextBytes(it) }.toLowerHex()
        )

        fun parse(value: String): ServerAccessToken? = value.takeIf {
            it.matches(Regex("[A-Za-z0-9._~-]{32,256}"))
        }?.let(::ServerAccessToken)
    }
}

internal sealed interface OwnedServerReply {
    data class Text(val value: String, val issuer: IssuerClaim? = null) : OwnedServerReply
    data object Quota : OwnedServerReply
    data object Unavailable : OwnedServerReply
    data object TooLong : OwnedServerReply
    data object Failed : OwnedServerReply
}

enum class ServerCacheClearResult { CLEARED, UNAVAILABLE, FAILED }

private class ServerSendAuthorization(val allowed: () -> Boolean)

internal interface OwnedServerClient : AutoCloseable {
    fun translate(
        original: String,
        target: TranslationTarget,
        token: ServerAccessToken,
        cancellation: RemoteCancellation,
        authorized: () -> Boolean
    ): OwnedServerReply

    fun clearCache(
        token: ServerAccessToken,
        cancellation: RemoteCancellation,
        authorized: () -> Boolean
    ): ServerCacheClearResult
}

/** Fixed build route, one transmission, bounded plaintext JSON and no redirects or retries. */
internal class OwnedServerTransport(
    private val endpoint: OwnedServerEndpoint,
    builder: OkHttpClient.Builder = OkHttpClient.Builder()
) : OwnedServerClient {
    private val client = builder.addNetworkInterceptor { chain ->
        val allowed = chain.request().tag(ServerSendAuthorization::class.java)
        val permit = chain.request().tag(RemoteSendPermit::class.java)
        if (allowed?.allowed?.invoke() != true || permit?.claim() != true) {
            throw IOException("Translation transmission no longer authorized")
        }
        chain.proceed(chain.request())
    }.followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    override fun translate(
        original: String,
        target: TranslationTarget,
        token: ServerAccessToken,
        cancellation: RemoteCancellation,
        authorized: () -> Boolean
    ): OwnedServerReply {
        if (original.isBlank() || original.length > 8192 ||
            !Charsets.UTF_8.newEncoder().canEncode(original)
        ) {
            return OwnedServerReply.Failed
        }
        if (original.toByteArray(Charsets.UTF_8).size > 15_000) return OwnedServerReply.TooLong
        if (!authorized() || cancellation.cancelled) return OwnedServerReply.Unavailable
        val json = JSONObject().put(
            "text",
            original
        ).put("target_lang", target.languageTag).put("response_version", "2").toString()
        val request = request(token, cancellation, authorized)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call = client.newCall(request)
        if (!cancellation.install(call::cancel)) return OwnedServerReply.Unavailable
        return try {
            if (!authorized() || cancellation.cancelled) return OwnedServerReply.Unavailable
            call.execute().use { response ->
                if (!authorized() || cancellation.cancelled) return OwnedServerReply.Unavailable
                when (response.code) {
                    401, 403 -> return OwnedServerReply.Unavailable
                    413 -> return OwnedServerReply.TooLong
                    429 -> return OwnedServerReply.Quota
                }
                if (response.code != 200 ||
                    response.body.contentType()?.let {
                        it.type == "application" && it.subtype == "json"
                    } != true
                ) {
                    return OwnedServerReply.Failed
                }
                val bytes = readBounded(response.body.byteStream(), 65_536)
                    ?: return OwnedServerReply.Failed
                val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                val parsed = parseOwnedServerResponse(text, original, target.languageTag)
                if (!authorized() ||
                    cancellation.cancelled
                ) {
                    OwnedServerReply.Unavailable
                } else {
                    parsed
                }
            }
        } catch (_: Exception) {
            // Request URLs, credentials and provider bodies must never enter logs or error messages.
            if (!authorized() ||
                cancellation.cancelled
            ) {
                OwnedServerReply.Unavailable
            } else {
                OwnedServerReply.Failed
            }
        } finally {
            cancellation.clear()
        }
    }

    override fun clearCache(
        token: ServerAccessToken,
        cancellation: RemoteCancellation,
        authorized: () -> Boolean
    ): ServerCacheClearResult {
        if (!authorized() || cancellation.cancelled) return ServerCacheClearResult.UNAVAILABLE
        val call = client.newCall(request(token, cancellation, authorized).delete().build())
        if (!cancellation.install(call::cancel)) return ServerCacheClearResult.UNAVAILABLE
        return try {
            if (!authorized() || cancellation.cancelled) return ServerCacheClearResult.UNAVAILABLE
            call.execute().use { response ->
                if (!authorized() || cancellation.cancelled || response.code in listOf(401, 403)) {
                    return ServerCacheClearResult.UNAVAILABLE
                }
                if (response.code != 200 || response.body.contentType()?.let {
                        it.type == "application" && it.subtype == "json"
                    } != true
                ) {
                    return ServerCacheClearResult.FAILED
                }
                val bytes = readBounded(response.body.byteStream(), 1024)
                    ?: return ServerCacheClearResult.FAILED
                val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                JsonReader(StringReader(text)).use { reader ->
                    reader.isLenient = false
                    reader.beginObject()
                    require(reader.nextName() == "cleared" && reader.peek() == JsonToken.BOOLEAN)
                    require(reader.nextBoolean() && !reader.hasNext())
                    reader.endObject()
                    require(reader.peek() == JsonToken.END_DOCUMENT)
                }
                if (!authorized() || cancellation.cancelled) {
                    ServerCacheClearResult.UNAVAILABLE
                } else {
                    ServerCacheClearResult.CLEARED
                }
            }
        } catch (_: Exception) {
            ServerCacheClearResult.FAILED
        } finally {
            cancellation.clear()
        }
    }

    private fun request(
        token: ServerAccessToken,
        cancellation: RemoteCancellation,
        authorized: () -> Boolean
    ): Request.Builder = Request.Builder().url(endpoint.url)
        .apply {
            val installation = token.installationId
            if (installation != null) {
                header("X-CodeClear-Installation", installation)
            } else {
                header("Authorization", token.authorization())
            }
        }.header("Accept", "application/json")
        .header("Cache-Control", "no-store")
        .tag(RemoteSendPermit::class.java, RemoteSendPermit())
        .tag(
            ServerSendAuthorization::class.java,
            ServerSendAuthorization {
                authorized() && !cancellation.cancelled
            }
        )

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

internal fun parseOwnedServerResponse(
    json: String,
    original: String,
    target: String
): OwnedServerReply = try {
    val values = mutableMapOf<String, String>()
    var issuer: Map<String, String>? = null
    val seen = mutableSetOf<String>()
    JsonReader(StringReader(json)).use { reader ->
        reader.isLenient = false
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(seen.add(name))
            if (name == "issuer") {
                val claims = mutableMapOf<String, String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    require(key in setOf("original", "translated", "evidence") && key !in claims)
                    require(reader.peek() == JsonToken.STRING)
                    claims[key] = reader.nextString()
                }
                reader.endObject()
                require(claims.keys.containsAll(setOf("original", "translated")))
                issuer = claims
            } else {
                require(name in setOf("translated_text", "target_lang", "source_sha256"))
                require(reader.peek() == JsonToken.STRING)
                values[name] = reader.nextString()
            }
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT)
    }
    require(seen == setOf("translated_text", "issuer", "target_lang", "source_sha256"))
    require(values["source_sha256"] == serverDigest(original) && values["target_lang"] == target)
    val translated = values.getValue("translated_text")
    require(translated.isNotBlank() && translated.length <= 16_384 && safeServerText(translated))
    val claims = requireNotNull(issuer)
    val name = claims.getValue("original")
    val translatedName = claims.getValue("translated")
    require(name.toByteArray(Charsets.UTF_8).size <= 512)
    require(translatedName.toByteArray(Charsets.UTF_8).size <= 1024)
    require(safeServerText(name) && safeServerText(translatedName))
    require(
        if (name.isEmpty()) {
            translatedName.isEmpty()
        } else {
            name.isNotBlank() && translatedName.isNotBlank() && original.contains(name)
        }
    )
    val evidence = claims["evidence"]
    val attribution = if (name.isEmpty()) {
        require(evidence.isNullOrEmpty())
        null
    } else if (evidence == null) {
        // Older responses can still supply the translation, but cannot establish an evidenced estimate.
        null
    } else {
        require(evidence.toByteArray(Charsets.UTF_8).size <= 2048 && safeServerText(evidence))
        requireNotNull(
            evidencedIssuer(
                original,
                name,
                translatedName,
                evidence,
                IssuerBasis.EXTRACTED_FROM_TEXT
            )
        )
    }
    for (pattern in preservedTokenPatterns) {
        val expected = pattern.findAll(original).groupingBy { it.value }.eachCount()
        val actual = pattern.findAll(translated).groupingBy { it.value }.eachCount()
        require(expected.all { (token, count) -> (actual[token] ?: 0) >= count })
    }
    OwnedServerReply.Text(translated, attribution)
} catch (_: Exception) {
    OwnedServerReply.Failed
}

// Numbers and URLs in the original must survive server translation; compiled once per process.
private val preservedTokenPatterns = listOf(
    Regex("[0-9]+(?:[.,:/-][0-9]+)*(?:%)?"),
    Regex("https?://[^\\s<>\"\\[\\]]+")
)

private fun safeServerText(value: String): Boolean =
    Charsets.UTF_8.newEncoder().canEncode(value) && value.none {
        it.code in 0..8 || it.code in 11..12 || it.code in 14..31 || it.code == 127
    }

private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray? = input.use { stream ->
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (output.size() <= limit) {
        val count = stream.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) return@use output.toByteArray()
        output.write(buffer, 0, count)
    }
    null
}

internal fun serverDigest(value: String): String = sha256Hex(value)
