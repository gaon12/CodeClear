package xyz.gaon.codeclear.probe

import android.content.Context
import android.icu.util.ULocale
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.view.translation.TranslationCapability
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationSpec
import android.view.translation.Translator
import androidx.annotation.RequiresApi
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun systemTranslationBackend(context: Context): SystemTranslationBackend =
    if (Build.VERSION.SDK_INT >=
        31
    ) {
        AndroidSystemTranslationBackend(context.applicationContext)
    } else {
        UnavailableSystemTranslationBackend
    }

private object UnavailableSystemTranslationBackend : SystemTranslationBackend {
    override val supported = false
    override fun identify(body: String): String? = null
    override fun capabilities(): List<SystemLanguagePair> = emptyList()
    override fun create(pair: SystemLanguagePair, complete: (SystemTranslationSession?) -> Unit) =
        complete(null)
}

/** API 31 types stay behind the version-gated factory, including on an API 30 runtime. */
@RequiresApi(31)
private class AndroidSystemTranslationBackend(context: Context) : SystemTranslationBackend {
    private val manager = context.getSystemService(TranslationManager::class.java)
    private val direct = Executor { it.run() }
    override val supported: Boolean get() = manager != null

    override fun identify(body: String): String? {
        requireWorker()
        return LanguageIdentification.getClient().use {
            // Keep the raw identified tag; system services may support more than the default SDK.
            Tasks.await(it.identifyLanguage(body), 15, TimeUnit.SECONDS)
        }
    }

    override fun capabilities(): List<SystemLanguagePair> {
        requireWorker()
        return manager?.getOnDeviceTranslationCapabilities(
            TranslationSpec.DATA_FORMAT_TEXT,
            TranslationSpec.DATA_FORMAT_TEXT
        )?.asSequence()?.filter {
            it.sourceSpec.dataFormat == TranslationSpec.DATA_FORMAT_TEXT &&
                it.targetSpec.dataFormat == TranslationSpec.DATA_FORMAT_TEXT &&
                it.state in setOf(
                    TranslationCapability.STATE_ON_DEVICE,
                    TranslationCapability.STATE_AVAILABLE_TO_DOWNLOAD,
                    TranslationCapability.STATE_DOWNLOADING
                )
        }?.map {
            SystemLanguagePair(
                it.sourceSpec.locale.toLanguageTag(),
                it.targetSpec.locale.toLanguageTag(),
                it.state == TranslationCapability.STATE_ON_DEVICE
            )
        }?.supportedSystemPairs().orEmpty()
    }

    override fun create(pair: SystemLanguagePair, complete: (SystemTranslationSession?) -> Unit) {
        requireWorker()
        val service = manager ?: return complete(null)
        // A fresh context avoids reusing a destroyed cached translator on Android 12.
        val context = TranslationContext.Builder(
            TranslationSpec(ULocale.forLanguageTag(pair.source), TranslationSpec.DATA_FORMAT_TEXT),
            TranslationSpec(ULocale.forLanguageTag(pair.target), TranslationSpec.DATA_FORMAT_TEXT)
        ).build()
        service.createOnDeviceTranslator(context, direct) {
            complete(it?.let { translator -> AndroidSystemSession(translator, direct) })
        }
    }

    override fun settingsAction(): (() -> Boolean)? {
        requireWorker()
        val intent = manager?.onDeviceTranslationSettingsActivityIntent ?: return null
        return {
            try {
                intent.send()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun requireWorker() = check(Looper.myLooper() != Looper.getMainLooper())
}

@RequiresApi(31)
private class AndroidSystemSession(
    private val translator: Translator,
    private val callbacks: Executor
) : SystemTranslationSession {
    private val cancellation = CancellationSignal()
    private val closed = AtomicBoolean()

    override fun translate(body: String, complete: (String?) -> Unit) {
        check(!closed.get())
        val request = SystemTranslationProtocol.request(body)
        translator.translate(request, cancellation, callbacks) { response ->
            complete(if (closed.get()) null else SystemTranslationProtocol.text(response))
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            cancellation.cancel()
        } finally {
            translator.destroy()
        }
    }
}
