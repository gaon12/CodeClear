package xyz.gaon.codeclear.probe

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

/** Does not download models until the coordinator supplies a per-request user approval. */
class MlKitLocalEngine : LocalEngine {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val scripts = ChineseScriptConverter()
    private var closed = false
    private var busy = false

    override fun run(
        input: LocalInput,
        allowModelDownload: Boolean,
        complete: (LocalResult) -> Unit
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed && !busy)
        busy = true
        worker.execute {
            val result = try {
                translate(input, allowModelDownload)
            } catch (_: Exception) {
                // Do not expose SDK exceptions: they can contain source text or device details.
                LocalResult.Failed
            }
            main.post {
                busy = false
                if (closed) worker.shutdown() else complete(result)
            }
        }
    }

    private fun translate(input: LocalInput, allowModelDownload: Boolean): LocalResult {
        val source = LanguageIdentification.getClient().use { identifier ->
            TranslateLanguage.fromLanguageTag(Tasks.await(identifier.identifyLanguage(input.body)))
        } ?: return LocalResult.UnknownLanguage
        if (source == input.target.modelCode) return ready(input.body, input, source, true)
        val options = TranslatorOptions.Builder().setSourceLanguage(source)
            .setTargetLanguage(input.target.modelCode).build()
        Translation.getClient(options).use { translator ->
            val downloaded = Tasks.await(
                RemoteModelManager.getInstance().getDownloadedModels(
                    TranslateRemoteModel::class.java
                )
            ).map { it.language }.toSet()
            val required = setOf(source, input.target.modelCode) - "en"
            if (!downloaded.containsAll(required)) {
                if (!allowModelDownload) return LocalResult.NeedsModels
                Tasks.await(
                    translator.downloadModelIfNeeded(
                        DownloadConditions.Builder().requireWifi().build()
                    )
                )
            }
            return ready(Tasks.await(translator.translate(input.body)), input, source, false)
        }
    }

    private fun ready(
        text: String,
        input: LocalInput,
        source: String,
        sameLanguage: Boolean
    ): LocalResult {
        if (text.isBlank() || text.length > 16384) return LocalResult.Failed
        val converted = scripts.convert(text, input.target)
        return if (converted.length > 16384) {
            LocalResult.Failed
        } else {
            LocalResult.Ready(converted, source, sameLanguage)
        }
    }

    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        closed = true
        // SDK Tasks have no cancellation contract. Keep their one worker slot until completion.
        if (!busy) worker.shutdown()
    }
}
