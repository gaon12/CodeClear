package xyz.gaon.codeclear.nearby

/** In-process synthetic owner only; no external component can configure a fixture session. */
object RelayValidationSession {
    var owner: NearbySharing? = null
    var allowed = false
}

class RelayValidationService : NearbyBackgroundService() {
    override val notificationId: Int = 2002
    override fun notificationTitle(): String = "[DRILL] " + super.notificationTitle()
    override fun sharing(): NearbySharing = checkNotNull(RelayValidationSession.owner)
    override fun permitted(): Boolean =
        RelayValidationSession.allowed && RelayValidationSession.owner != null
    override fun stopSharing() {
        RelayValidationSession.allowed = false
        RelayValidationSession.owner?.configure(false)
    }
}
