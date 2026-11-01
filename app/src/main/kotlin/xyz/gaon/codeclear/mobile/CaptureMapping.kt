package xyz.gaon.codeclear.mobile

import xyz.gaon.codeclear.probe.ProbeBodyEvidence
import xyz.gaon.codeclear.probe.ProbeOrigin
import xyz.gaon.codeclear.probe.ProbeRecord
import xyz.gaon.codeclear.probe.ProbeRoute
import xyz.gaon.codeclear.reception.CountryEvidence
import xyz.gaon.codeclear.reception.ReceptionCountry

/** The caller supplies actual verified system packages, radio capability, and current route consent. */
fun ProbeRecord.toHistoryCapture(
    systemPackages: Set<String>,
    radioPresent: Boolean,
    notificationConsent: Boolean,
    accessibilityConsent: Boolean
): CapturedAlert? {
    val source = observation
    val consent = when (source.route) {
        ProbeRoute.NOTIFICATION -> notificationConsent
        ProbeRoute.ACCESSIBILITY -> accessibilityConsent
    }
    val reviewedBody = when (source.route) {
        ProbeRoute.NOTIFICATION ->
            source.bodyEvidence == ProbeBodyEvidence.EXPANDED_NOTIFICATION_FIELD ||
                source.bodyEvidence == ProbeBodyEvidence.SOURCE_NOTIFICATION_PAYLOAD

        ProbeRoute.ACCESSIBILITY -> source.bodyEvidence == ProbeBodyEvidence.VISIBLE_DIALOG_FIELD
    }
    if (!radioPresent || !consent || source.origin != ProbeOrigin.SYSTEM_CANDIDATE ||
        source.packageName !in systemPackages ||
        !reviewedBody || source.platformBoundaryPossible ||
        source.limited || source.groupSummary || source.body.isBlank()
    ) {
        return null
    }
    val fields = source.broadcastFields
    val identity = fields?.let {
        "broadcast:${it.format}:${it.category}:${it.serial}:${it.receivedAtMillis}"
    } ?: source.sourceObservationKey?.takeIf { it.length <= 512 }
    return CapturedAlert(
        sourcePackage = source.packageName,
        source = when {
            source.bodyEvidence == ProbeBodyEvidence.SOURCE_NOTIFICATION_PAYLOAD ->
                CaptureSource.ORIGINAL_NOTIFICATION

            source.route == ProbeRoute.NOTIFICATION -> CaptureSource.NOTIFICATION

            else -> CaptureSource.DIALOG
        },
        title = source.title,
        original = source.body,
        capturedAtMillis = source.capturedAtMillis,
        postedAtMillis = source.postedAtMillis,
        receivedAtMillis = fields?.receivedAtMillis,
        sourceIdentity = identity,
        headingHint = source.headingHint.name,
        context = ReceptionContext(
            fields?.countryIso?.let {
                ReceptionCountry(it, CountryEvidence.CBS_PLMN)
            }
        ),
        broadcast = fields?.let {
            BroadcastMetadata(
                it.format,
                it.serial,
                it.category,
                it.priority,
                it.cmasClass,
                it.etwsType
            )
        }
    )
}
