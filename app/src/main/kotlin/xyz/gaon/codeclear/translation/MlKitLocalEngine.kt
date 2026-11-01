package xyz.gaon.codeclear.translation

import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Does not download models until the coordinator supplies a per-request user approval. */
class MlKitLocalEngine : TranslationEngine {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val scripts = ChineseScriptConverter()
    private var closed = false
    private var busy = false

    override fun run(
        input: TranslationInput,
        allowModelDownload: Boolean,
        complete: (TranslationResult) -> Unit
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed && !busy)
        busy = true
        worker.execute {
            val result = try {
                translate(input, allowModelDownload)
            } catch (_: Exception) {
                // Do not expose SDK exceptions: they can contain source text or device details.
                TranslationResult.Failed
            }
            main.post {
                busy = false
                if (closed) worker.shutdown() else complete(result)
            }
        }
    }

    private fun translate(input: TranslationInput, allowModelDownload: Boolean): TranslationResult {
        val source = LanguageIdentification.getClient().use { identifier ->
            TranslateLanguage.fromLanguageTag(
                Tasks.await(identifier.identifyLanguage(input.body), 15, TimeUnit.SECONDS)
            )
        } ?: return TranslationResult.UnknownLanguage
        if (source == input.target.modelCode) return ready(input.body, input, source, true)
        val options = TranslatorOptions.Builder().setSourceLanguage(source)
            .setTargetLanguage(input.target.modelCode).build()
        Translation.getClient(options).use { translator ->
            val downloaded = Tasks.await(
                RemoteModelManager.getInstance().getDownloadedModels(
                    TranslateRemoteModel::class.java
                ),
                15,
                TimeUnit.SECONDS
            ).map { it.language }.toSet()
            val required = setOf(source, input.target.modelCode) - "en"
            if (!downloaded.containsAll(required)) {
                if (!allowModelDownload) return TranslationResult.NeedsModels
                Tasks.await(
                    translator.downloadModelIfNeeded(
                        DownloadConditions.Builder().requireWifi().build()
                    ),
                    3,
                    TimeUnit.MINUTES
                )
            }
            return ready(
                Tasks.await(translator.translate(input.body), 20, TimeUnit.SECONDS),
                input,
                source,
                false
            )
        }
    }

    private fun ready(
        text: String,
        input: TranslationInput,
        source: String,
        sameLanguage: Boolean
    ): TranslationResult {
        if (text.isBlank() || text.length > 16384) return TranslationResult.Failed
        val converted = scripts.convert(text, input.target)
        return if (converted.length > 16384) {
            TranslationResult.Failed
        } else {
            TranslationResult.Ready(converted, source, sameLanguage)
        }
    }

    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        closed = true
        // The active bounded operation owns client cleanup before its worker is shut down.
        if (!busy) worker.shutdown()
    }
}
