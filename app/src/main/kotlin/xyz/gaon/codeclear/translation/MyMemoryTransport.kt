package xyz.gaon.codeclear.translation

import android.text.Html
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

sealed interface MyMemoryReply {
    data class Text(val value: String) : MyMemoryReply
    data object Quota : MyMemoryReply
    data object Failed : MyMemoryReply
}

/** A cancellation may win before or after a call is installed. No new call starts afterward. */
class RemoteCancellation {
    @Volatile var cancelled = false
        private set
    private var active: (() -> Unit)? = null

    @Synchronized fun install(stop: () -> Unit): Boolean {
        if (cancelled) {
            stop()
            return false
        }
        active = stop
        return true
    }

    @Synchronized fun clear() {
        active = null
    }

    @Synchronized fun cancel() {
        cancelled = true
        active?.invoke()
        active = null
    }
}

fun interface MyMemoryTransport {
    fun translate(
        text: String,
        source: String,
        target: String,
        cancellation: RemoteCancellation
    ): MyMemoryReply
}

/** The fixed endpoint, disabled redirects/retries, and bounded bodies prevent accidental forwarding. */
class HttpsMyMemoryTransport(builder: OkHttpClient.Builder = OkHttpClient.Builder()) :
    MyMemoryTransport {
    private val client = builder
        .addNetworkInterceptor { chain ->
            // HTTP status follow-ups can occur even when connection retries are disabled.
            val permit = chain.request().tag(RemoteSendPermit::class.java)
            if (permit?.claim() != true) throw IOException("Repeated transmission blocked")
            chain.proceed(chain.request())
        }
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).build()

    override fun translate(
        text: String,
        source: String,
        target: String,
        cancellation: RemoteCancellation
    ): MyMemoryReply {
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 500)
        require(text.none { isRemoteParagraphBoundary(it) || it == '\u0000' })
        require(Charsets.UTF_8.newEncoder().canEncode(text))
        require(validLanguage(source) && validLanguage(target))
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("api.mymemory.translated.net")
            .addPathSegment("get").addQueryParameter("q", text)
            .addQueryParameter("langpair", "$source|$target").build()
        val call = client.newCall(
            Request.Builder().url(
                url
            ).tag(RemoteSendPermit::class.java, RemoteSendPermit()).get().build()
        )
        if (!cancellation.install(call::cancel)) return MyMemoryReply.Failed
        return try {
            call.execute().use { response ->
                if (response.code == 429) return MyMemoryReply.Quota
                if (!response.isSuccessful) return MyMemoryReply.Failed
                val body = response.body
                if (body.contentLength() >
                    MyMemoryPlan.MAX_RESPONSE_BYTES
                ) {
                    return MyMemoryReply.Failed
                }
                val bytes = body.byteStream().use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= MyMemoryPlan.MAX_RESPONSE_BYTES) {
                        val count = stream.read(
                            buffer,
                            0,
                            minOf(
                                buffer.size,
                                MyMemoryPlan.MAX_RESPONSE_BYTES + 1 - output.size()
                            )
                        )
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (bytes.size > MyMemoryPlan.MAX_RESPONSE_BYTES ||
                    cancellation.cancelled
                ) {
                    return MyMemoryReply.Failed
                }
                parseMyMemoryResponse(
                    Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                )
            }
        } catch (_: IOException) {
            // URLs and provider exceptions can contain submitted text; never log either.
            MyMemoryReply.Failed
        } finally {
            cancellation.clear()
        }
    }

    private fun validLanguage(value: String): Boolean = value.length in 2..32 &&
        value.matches(Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*"))
}

internal fun parseMyMemoryResponse(body: String): MyMemoryReply = try {
    val response = JSONObject(body)
    if (response.optBoolean("quotaFinished") || response.optInt("responseStatus") == 429) {
        MyMemoryReply.Quota
    } else if (response.optInt("responseStatus") != 200) {
        MyMemoryReply.Failed
    } else {
        val raw = response.optJSONObject("responseData")?.opt("translatedText") as? String
        if (raw == null || raw.isBlank() || raw.length > MyMemoryPlan.MAX_OUTPUT) {
            MyMemoryReply.Failed
        } else {
            // Render entity escapes as plain text. Provider markup never enters a WebView.
            val text = Html.fromHtml(
                raw.replace("<", "&lt;").replace(">", "&gt;"),
                Html.FROM_HTML_MODE_LEGACY
            ).toString().trim()
            if (text.isBlank() || text.length > MyMemoryPlan.MAX_OUTPUT || '\u0000' in text) {
                MyMemoryReply.Failed
            } else {
                MyMemoryReply.Text(text)
            }
        }
    }
} catch (_: Exception) {
    MyMemoryReply.Failed
}

internal class RemoteSendPermit {
    private val used = AtomicBoolean()
    fun claim(): Boolean = used.compareAndSet(false, true)
}
