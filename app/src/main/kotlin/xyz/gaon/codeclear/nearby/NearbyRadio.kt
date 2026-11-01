package xyz.gaon.codeclear.nearby

interface NearbyRadio : AutoCloseable {
    fun start()
    fun publish(
        id: Long,
        title: String,
        body: String,
        capturedAt: Long,
        expiresAt: Long = Long.MAX_VALUE,
        occurrence: RelayOccurrence = RelayOccurrence()
    )
}

fun interface NearbyRadioFactory {
    fun create(
        sender: Boolean,
        known: (String) -> Boolean,
        received: (NearbyOriginal) -> Unit,
        status: (NearbyStatus) -> Unit
    ): NearbyRadio
}
