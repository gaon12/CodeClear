package xyz.gaon.codeclear.probe

import xyz.gaon.codeclear.capture.NotificationCaptureService

/** Keep the granted Android component name stable while capture implementation evolves. */
class ProbeNotificationListener : NotificationCaptureService() {
    companion object {
        fun inspectConsentedSources() = NotificationCaptureService.inspectConsentedSources()
    }
}
