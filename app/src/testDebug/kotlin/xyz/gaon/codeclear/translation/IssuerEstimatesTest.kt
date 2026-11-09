package xyz.gaon.codeclear.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssuerEstimatesTest {
    @Test fun boundaryLabelsRemainExplicitlyFallibleAndKeepExactEvidence() {
        val original = "[DRILL] Gate 3. [행정안전부]"
        val claim = estimateIssuer(original)!!
        assertEquals("행정안전부", claim.originalName)
        assertEquals("[행정안전부]", claim.sourceExcerpt)
        assertEquals(IssuerBasis.TEXT_PATTERN_ESTIMATE, claim.basis)
        assertNull(claim.countryIso)
        assertNull(claim.translatedName)
        assertTrue(original.contains(claim.sourceExcerpt!!))
        assertEquals("USGS", estimateIssuer("[USGS] [DRILL] Gate 3.")!!.originalName)
    }

    @Test fun unknownAmbiguousInteriorAndLocationOnlyLabelsDoNotInventASender() {
        for (body in listOf(
            "[DRILL] Seoul, call 112.", "[DRILL] [Unknown authority]",
            "[제주] [DRILL] Gate 3.", "[DRILL] text [USGS] more text",
            "[USGS] [DRILL] [NWS]", "[DRILL] [광주]",
            "[USGS] " + "[DRILL]".repeat(33), "x".repeat(8193),
            "[USGS] \u202e[DRILL]"
        )) {
            assertNull(body, estimateIssuer(body))
        }
    }

    @Test fun aiClaimsNeedLiteralSafeCorrelatedEvidenceWithoutAnAuthenticityClaim() {
        val original = "[DRILL] Issued by Agency X."
        val claim = evidencedIssuer(
            original,
            "Agency X",
            "기관 X",
            "Issued by Agency X.",
            IssuerBasis.EXTRACTED_FROM_TEXT
        )!!
        assertEquals(IssuerBasis.EXTRACTED_FROM_TEXT, claim.basis)
        assertNull(
            evidencedIssuer(
                original,
                "Agency X",
                "x".repeat(5000),
                "Issued by Agency X.",
                IssuerBasis.EXTRACTED_FROM_TEXT
            )
        )
        assertNull(
            evidencedIssuer(
                original,
                "Agency Y",
                "기관 Y",
                "Issued by Agency X.",
                IssuerBasis.EXTRACTED_FROM_TEXT
            )
        )
        assertNull(
            evidencedIssuer(
                original,
                "Agency X",
                "기관 X",
                "missing Agency X",
                IssuerBasis.EXTRACTED_FROM_TEXT
            )
        )
        assertNull(
            evidencedIssuer(
                original,
                "Agency X",
                "기관 X",
                "Issued by Agency X.",
                IssuerBasis.STRUCTURED_SOURCE_CLAIM
            )
        )
        assertNull(
            evidencedIssuer(
                original,
                "Agency X",
                "기관\u202eX",
                "Issued by Agency X.",
                IssuerBasis.EXTRACTED_FROM_TEXT
            )
        )
    }
}
