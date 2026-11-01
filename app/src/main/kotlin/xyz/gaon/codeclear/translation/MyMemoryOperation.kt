package xyz.gaon.codeclear.translation

/** One attempt owns one budget reservation and never retries or falls back to another provider. */
class MyMemoryOperation(
    private val transport: MyMemoryTransport,
    private val budget: RemoteDailyBudget,
    private val identify: (String) -> String?,
    private val convert: (String, TranslationTarget) -> String,
    private val allowed: () -> Boolean,
    private val elapsed: () -> Long
) {
    fun run(input: TranslationInput, cancellation: RemoteCancellation): TranslationResult {
        val deadline = elapsed() + 45_000
        fun current() = allowed() && !cancellation.cancelled && elapsed() < deadline
        if (!current()) return TranslationResult.Unavailable
        val source = identify(input.body)?.takeIf {
            it.length in 2..32 && it.matches(Regex("[a-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")) &&
                it != "und"
        } ?: return TranslationResult.UnknownLanguage
        if (!current()) return TranslationResult.Unavailable
        if (source == input.target.modelCode) {
            return ready(input.body, input.target, source, true)
        }
        val plan = try {
            MyMemoryPlan.segments(input.body)
        } catch (_: IllegalArgumentException) {
            return TranslationResult.TooLong
        }
        val characters = plan.filter { it.translate }.sumOf { it.text.length }
        if (characters == 0) return TranslationResult.UnknownLanguage
        if (!current()) return TranslationResult.Unavailable
        val reserved = try {
            budget.reserve(characters)
        } catch (_: RuntimeException) {
            return TranslationResult.Failed
        }
        if (!reserved) return TranslationResult.Quota
        val target = when (input.target) {
            TranslationTarget.SIMPLIFIED_CHINESE -> "zh-CN"
            TranslationTarget.TRADITIONAL_CHINESE -> "zh-TW"
            else -> input.target.languageTag
        }
        val output = StringBuilder()
        for (segment in plan) {
            if (!current()) return TranslationResult.Failed
            if (!segment.translate) {
                output.append(segment.text)
            } else {
                when (val reply = transport.translate(segment.text, source, target, cancellation)) {
                    is MyMemoryReply.Text -> output.append(reply.value)

                    MyMemoryReply.Quota -> {
                        budget.exhaust()
                        return TranslationResult.Quota
                    }

                    MyMemoryReply.Failed -> return TranslationResult.Failed
                }
            }
            if (output.length > MyMemoryPlan.MAX_OUTPUT) return TranslationResult.Failed
        }
        if (!current()) return TranslationResult.Failed
        return ready(output.toString(), input.target, source, false)
    }

    private fun ready(
        text: String,
        target: TranslationTarget,
        source: String,
        same: Boolean
    ): TranslationResult {
        val output = convert(text, target)
        return if (output.isBlank() || output.length > MyMemoryPlan.MAX_OUTPUT) {
            TranslationResult.Failed
        } else {
            TranslationResult.Ready(output, source, same)
        }
    }
}
