package xyz.gaon.codeclear.translation

import xyz.gaon.codeclear.probe.LocalInput
import xyz.gaon.codeclear.probe.LocalResult
import xyz.gaon.codeclear.probe.LocalTarget

/** One attempt owns one budget reservation and never retries or falls back to another provider. */
class MyMemoryOperation(
    private val transport: MyMemoryTransport,
    private val budget: RemoteDailyBudget,
    private val identify: (String) -> String?,
    private val convert: (String, LocalTarget) -> String,
    private val allowed: () -> Boolean,
    private val elapsed: () -> Long
) {
    fun run(input: LocalInput, cancellation: RemoteCancellation): LocalResult {
        val deadline = elapsed() + 45_000
        fun current() = allowed() && !cancellation.cancelled && elapsed() < deadline
        if (!current()) return LocalResult.Unavailable
        val source = identify(input.body)?.takeIf {
            it.length in 2..32 && it.matches(Regex("[a-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")) &&
                it != "und"
        } ?: return LocalResult.UnknownLanguage
        if (!current()) return LocalResult.Unavailable
        if (source == input.target.modelCode) {
            return ready(input.body, input.target, source, true)
        }
        val plan = try {
            MyMemoryPlan.segments(input.body)
        } catch (_: IllegalArgumentException) {
            return LocalResult.TooLong
        }
        val characters = plan.filter { it.translate }.sumOf { it.text.length }
        if (characters == 0) return LocalResult.UnknownLanguage
        if (!current()) return LocalResult.Unavailable
        val reserved = try {
            budget.reserve(characters)
        } catch (_: RuntimeException) {
            return LocalResult.Failed
        }
        if (!reserved) return LocalResult.Quota
        val target = when (input.target) {
            LocalTarget.SIMPLIFIED_CHINESE -> "zh-CN"
            LocalTarget.TRADITIONAL_CHINESE -> "zh-TW"
            else -> input.target.languageTag
        }
        val output = StringBuilder()
        for (segment in plan) {
            if (!current()) return LocalResult.Failed
            if (!segment.translate) {
                output.append(segment.text)
            } else {
                when (val reply = transport.translate(segment.text, source, target, cancellation)) {
                    is MyMemoryReply.Text -> output.append(reply.value)

                    MyMemoryReply.Quota -> {
                        budget.exhaust()
                        return LocalResult.Quota
                    }

                    MyMemoryReply.Failed -> return LocalResult.Failed
                }
            }
            if (output.length > MyMemoryPlan.MAX_OUTPUT) return LocalResult.Failed
        }
        if (!current()) return LocalResult.Failed
        return ready(output.toString(), input.target, source, false)
    }

    private fun ready(
        text: String,
        target: LocalTarget,
        source: String,
        same: Boolean
    ): LocalResult {
        val output = convert(text, target)
        return if (output.isBlank() || output.length > MyMemoryPlan.MAX_OUTPUT) {
            LocalResult.Failed
        } else {
            LocalResult.Ready(output, source, same)
        }
    }
}
