package xyz.gaon.codeclear.mobile

import xyz.gaon.codeclear.R
import xyz.gaon.codeclear.reception.CountryEvidence

enum class NationalAlertType(val label: Int) {
    EXTREME_EMERGENCY(R.string.mobile_type_extreme),
    EMERGENCY(R.string.mobile_type_emergency),
    PUBLIC_SAFETY(R.string.mobile_type_safety),
    MISSING_PERSON(R.string.mobile_type_missing)
}

/** Reviewed KR KPAS channel identifiers; categories carry no universal numeric severity ordering. */
fun nationalAlertType(capture: CapturedAlert): NationalAlertType? {
    val country = capture.context.country ?: return null
    if (capture.source == CaptureSource.PEER || country.evidence != CountryEvidence.CBS_PLMN ||
        country.iso != "KR" || capture.broadcast?.format != 1
    ) {
        return null
    }
    return when (capture.broadcast.category) {
        4370, 4383 -> NationalAlertType.EXTREME_EMERGENCY
        4371, 4384 -> NationalAlertType.EMERGENCY
        4372, 4385 -> NationalAlertType.PUBLIC_SAFETY
        4379, 4392 -> NationalAlertType.MISSING_PERSON
        else -> null
    }
}
