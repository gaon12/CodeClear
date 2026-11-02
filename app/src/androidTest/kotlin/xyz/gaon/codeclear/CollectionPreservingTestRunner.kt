package xyz.gaon.codeclear

import android.app.UiAutomation
import androidx.test.runner.AndroidJUnitRunner

/** The isolated validation app must not suppress the owner's enabled collection services. */
class CollectionPreservingTestRunner : AndroidJUnitRunner() {
    override fun getUiAutomation(): UiAutomation? = getUiAutomation(0)

    override fun getUiAutomation(flags: Int): UiAutomation? =
        super.getUiAutomation(flags or UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
}
