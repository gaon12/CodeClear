package xyz.gaon.codeclear.translation

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class ServerAccessState(
    val configured: Boolean = false,
    val loaded: Boolean = false,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val revision: Long = 0,
    val credentialId: String = "",
    val cacheResult: ServerCacheClearResult? = null
)

private val identifiedLanguage = Regex("[a-z]{2,8}")

/** Persisted consent is bound to a credential; replacing it never authorizes another account. */
internal class OwnedServerEngine(
    context: Context,
    private val endpoint: OwnedServerEndpoint? = OwnedServerEndpoint.configured(),
    private val changed: (ServerAccessState) -> Unit = {},
    suppliedStorage: ServerTokenStorage? = null,
    clientFactory: (OwnedServerEndpoint) -> OwnedServerClient = { OwnedServerTransport(it) },
    private val identify: (String) -> String? = ::identifyServerSource
) : TranslationEngine {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1))
    private val application = context.applicationContext
    private val storage = lazy {
        suppliedStorage ?: ServerCredentialStore(application, requireNotNull(endpoint).scope)
    }
    private val client = lazy {
        clientFactory(requireNotNull(endpoint)).also { if (closed) it.close() }
    }
    private val scripts = ChineseScriptConverter()
    private val tokenLock = Any()

    @Volatile private var closed = false

    @Volatile private var epoch = 0L

    @Volatile private var authorized = false

    @Volatile private var consentCredential = ""

    @Volatile private var token: ServerAccessToken? = null
    private var active: RemoteCancellation? = null
    private var activeTimeout: Runnable? = null
    private var maintenance: RemoteCancellation? = null
    var access = ServerAccessState(configured = endpoint != null, loaded = endpoint == null)
        private set

    init {
        checkMain()
        if (endpoint != null) {
            worker.execute {
                val loaded = runCatching { storage.value.load() }.getOrNull()
                acceptCredential(0L, loaded)
                main.post {
                    if (!closed && epoch == 0L) publish(access.copy(loaded = true).withToken())
                }
            }
        }
    }

    fun authorize(value: Boolean, credentialId: String) {
        checkMain()
        consentCredential = credentialId
        authorized = value && !closed && endpoint != null &&
            credentialId.matches(Regex("[0-9a-f]{64}")) && !(access.loaded && access.busy)
        if (!authorized) cancel()
    }

    override fun run(
        input: TranslationInput,
        allowModelDownload: Boolean,
        complete: (TranslationResult) -> Unit
    ) {
        checkMain()
        check(!closed && active == null)
        if (input.engine != TranslationEngineKind.OWNED_SERVER) {
            complete(TranslationResult.Unavailable)
            return
        }
        if (!authorized || endpoint == null || (access.loaded && access.busy)) {
            complete(TranslationResult.Unavailable)
            return
        }
        val cancellation = RemoteCancellation()
        val revision = epoch
        active = cancellation
        val timeout = Runnable { cancellation.cancel() }
        activeTimeout = timeout
        main.postDelayed(timeout, 65_000)
        try {
            worker.execute {
                val credential = token
                val allowed = {
                    !closed && authorized && epoch == revision && !cancellation.cancelled &&
                        credential != null && credential.fingerprint == consentCredential
                }
                val result = try {
                    if (!allowed() || credential == null) {
                        TranslationResult.Unavailable
                    } else {
                        val source = runCatching { identify(input.body) }.getOrNull()
                            ?.takeIf { it.matches(identifiedLanguage) } ?: "und"
                        if (!allowed()) {
                            TranslationResult.Unavailable
                        } else if (source == input.target.modelCode) {
                            TranslationResult.Ready(
                                scripts.convert(input.body, input.target),
                                source,
                                true
                            )
                        } else {
                            when (
                                val reply = client.value.translate(
                                    input.body,
                                    input.target,
                                    credential,
                                    cancellation,
                                    allowed
                                )
                            ) {
                                is OwnedServerReply.Text -> TranslationResult.Ready(
                                    reply.value,
                                    source,
                                    false
                                )

                                OwnedServerReply.Quota -> TranslationResult.Quota

                                OwnedServerReply.Unavailable -> TranslationResult.Unavailable

                                OwnedServerReply.TooLong -> TranslationResult.TooLong

                                OwnedServerReply.Failed -> TranslationResult.Failed
                            }
                        }
                    }
                } catch (_: Exception) {
                    TranslationResult.Failed
                }
                main.post {
                    main.removeCallbacks(timeout)
                    if (activeTimeout === timeout) activeTimeout = null
                    if (active === cancellation) active = null
                    if (!closed) complete(if (allowed()) result else TranslationResult.Unavailable)
                }
            }
        } catch (_: RejectedExecutionException) {
            main.removeCallbacks(timeout)
            if (activeTimeout === timeout) activeTimeout = null
            active = null
            complete(TranslationResult.Failed)
        }
    }

    fun saveAccess(value: String): Boolean {
        checkMain()
        if (ServerAccessToken.parse(value) == null || !canMaintain()) return false
        mutateCredential { storage.value.save(value) }
        return true
    }

    fun removeAccess(): Boolean {
        checkMain()
        if (!canMaintain()) return false
        mutateCredential { storage.value.clear() }
        return true
    }

    private fun mutateCredential(action: () -> Unit) {
        invalidateCredential()
        val revision = epoch
        publish(access.copy(busy = true, failed = false, revision = revision, cacheResult = null))
        try {
            worker.execute {
                val success = runCatching(action).isSuccess
                val loaded = runCatching { storage.value.load() }.getOrNull()
                acceptCredential(revision, loaded)
                main.post {
                    if (!closed && epoch == revision) {
                        publish(access.copy(busy = false, failed = !success).withToken())
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            publish(access.copy(busy = false, failed = true).withToken())
        }
    }

    /** User-requested deletion is independent of translation consent and always leaves it paused. */
    fun clearCache(): Boolean {
        checkMain()
        val credential = token ?: return false
        if (!canMaintain()) return false
        invalidateCredential()
        val revision = epoch
        val cancellation = RemoteCancellation()
        maintenance = cancellation
        publish(access.copy(busy = true, failed = false, revision = revision, cacheResult = null))
        try {
            worker.execute {
                val result = try {
                    client.value.clearCache(credential, cancellation) {
                        !closed && epoch == revision && !cancellation.cancelled &&
                            token === credential
                    }
                } catch (_: Exception) {
                    ServerCacheClearResult.FAILED
                }
                main.post {
                    if (maintenance === cancellation) maintenance = null
                    if (!closed && epoch == revision) {
                        authorized = false
                        publish(access.copy(busy = false, cacheResult = result).withToken())
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            maintenance = null
            publish(access.copy(busy = false, cacheResult = ServerCacheClearResult.FAILED))
        }
        return true
    }

    fun cancel() {
        checkMain()
        active?.cancel()
        activeTimeout?.let(main::removeCallbacks)
        activeTimeout = null
    }

    fun close() {
        checkMain()
        if (closed) return
        synchronized(tokenLock) {
            closed = true
            authorized = false
            token = null
        }
        active?.cancel()
        activeTimeout?.let(main::removeCallbacks)
        activeTimeout = null
        maintenance?.cancel()
        worker.queue.clear()
        // TLS socket shutdown can perform I/O; also wait for any in-flight client creation.
        worker.execute { if (client.isInitialized()) client.value.close() }
        worker.shutdown()
    }

    private fun invalidateCredential() {
        synchronized(tokenLock) {
            epoch++
            authorized = false
            consentCredential = ""
        }
        cancel()
        maintenance?.cancel()
    }

    private fun acceptCredential(revision: Long, value: ServerAccessToken?) {
        synchronized(tokenLock) {
            if (!closed && epoch == revision) token = value
        }
    }

    private fun canMaintain(): Boolean =
        !closed && endpoint != null && access.loaded && !access.busy

    private fun ServerAccessState.withToken(): ServerAccessState = copy(
        ready = token != null,
        credentialId = token?.fingerprint.orEmpty()
    )

    private fun publish(value: ServerAccessState) {
        access = value
        changed(value)
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
}

private fun identifyServerSource(text: String): String? = LanguageIdentification.getClient().use {
    Tasks.await(it.identifyLanguage(text), 15, TimeUnit.SECONDS)
}
