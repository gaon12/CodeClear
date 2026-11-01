package xyz.gaon.codeclear.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.gaon.codeclear.probe.ProbeAccessibilityService

class AccessibilityGrantTest {
    private val owner = "xyz.gaon.codeclear"
    private val service = "$owner.probe.ProbeAccessibilityService"

    @Test fun configuredGrantSurvivesAnEmptyBoundListAndAcceptsCanonicalComponents() {
        assertTrue(hasAccessibilityGrant("$owner/$service", owner, service))
        assertTrue(
            hasAccessibilityGrant(
                "other/other.Service:$owner/.probe.ProbeAccessibilityService",
                owner,
                service
            )
        )
    }

    @Test fun removedMalformedAndSimilarComponentsNeverCountAsThisGrant() {
        for (value in listOf(
            null,
            "",
            "null",
            "$owner/",
            "$owner/$service/extra",
            "other/$service",
            "$owner/$service.extra",
            "x".repeat(32769)
        )) {
            assertFalse(hasAccessibilityGrant(value, owner, service))
        }
    }
}
