package xyz.gaon.codeclear.capture

enum class ProbeSourceActionFailure { UNAVAILABLE, TIMEOUT, UNSUPPORTED, LIMITED }

data class ProbeSourceActionState(
    val available: Boolean = false,
    val busy: Boolean = false,
    val returnedMessages: Int? = null,
    val recognizedTypes: Boolean = false,
    val importedMessages: Int = 0,
    val matchingObservedBodies: Int = 0,
    val failure: ProbeSourceActionFailure? = null
)

fun inspectSourcePayloadTypes(names: List<String>): ProbeSourceActionState = when {
    names.isEmpty() -> ProbeSourceActionState(failure = ProbeSourceActionFailure.UNSUPPORTED)

    names.size > 40 -> ProbeSourceActionState(failure = ProbeSourceActionFailure.LIMITED)

    else -> ProbeSourceActionState(
        returnedMessages = names.size,
        recognizedTypes = names.all { it == "android.telephony.SmsCbMessage" }
    )
}
