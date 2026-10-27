package xyz.gaon.codeclear.probe

/** Package candidates do not establish full-body capture support or public-alert provenance. */
object ProbeSourcePolicy {
    fun classifyRadio(telephony: Boolean, radioAccess: Boolean?): ProbeRadio = when {
        radioAccess == null -> if (telephony) ProbeRadio.PRESENT else ProbeRadio.ABSENT
        telephony && radioAccess -> ProbeRadio.PRESENT
        !telephony && !radioAccess -> ProbeRadio.ABSENT
        else -> ProbeRadio.UNKNOWN
    }

    fun mayCollect(
        radio: ProbeRadio,
        consent: Boolean,
        systemAccess: Boolean,
        diagnosticOverride: Boolean
    ): Boolean = consent && systemAccess && (radio == ProbeRadio.PRESENT || diagnosticOverride)

    val candidatePackages = setOf(
        "com.android.cellbroadcastreceiver",
        "com.google.android.cellbroadcastreceiver"
    )

    fun classify(
        packageName: String,
        verifiedSystemPackages: Set<String>,
        shellFixtureEnabled: Boolean,
        notificationTag: String? = null
    ): ProbeOrigin? = when {
        packageName in candidatePackages && packageName in verifiedSystemPackages ->
            ProbeOrigin.SYSTEM_CANDIDATE

        shellFixtureEnabled &&
            packageName == "com.android.shell" &&
            notificationTag == "test_alert" ->
            ProbeOrigin.SHELL_FIXTURE

        else -> null
    }
}
