package xyz.gaon.codeclear.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerTranslationDraftTest {
    private val digest = "a".repeat(64)

    @Test
    fun issuerRemainsAClaimAndResponsesRequireMatchingOriginalAndTarget() {
        val response = ServerTranslationDraft(
            digest,
            "ko",
            "en",
            "[DRILL] Translation",
            IssuerClaim("발송 기관", "Issuing authority", IssuerBasis.EXTRACTED_FROM_TEXT, "KR")
        )
        assertTrue(response.matches(digest, "en"))
        assertFalse(response.matches("b".repeat(64), "en"))
        assertFalse(response.matches(digest, "ja"))
        assertTrue(response.issuer?.basis == IssuerBasis.EXTRACTED_FROM_TEXT)
        assertTrue(response.copy(issuer = null).issuer == null)
    }

    @Test
    fun malformedAndOversizedDraftFieldsAreRejected() {
        rejected { ServerTranslationDraft("bad", "ko", "en", "text") }
        rejected { ServerTranslationDraft(digest, "ko", "ko", "text") }
        rejected { ServerTranslationDraft(digest, "../ko", "en", "text") }
        rejected { ServerTranslationDraft(digest, "ko", "en", " ") }
        rejected { ServerTranslationDraft(digest, "ko", "en", "x".repeat(16_385)) }
        rejected { IssuerClaim(" ", null, IssuerBasis.STRUCTURED_SOURCE_CLAIM) }
        rejected { IssuerClaim("x".repeat(257), null, IssuerBasis.EXTRACTED_FROM_TEXT) }
        rejected { IssuerClaim("Issuer", " ", IssuerBasis.EXTRACTED_FROM_TEXT) }
        rejected { IssuerClaim("Issuer", null, IssuerBasis.EXTRACTED_FROM_TEXT, "kr") }
        rejected { IssuerClaim("Issuer", null, IssuerBasis.EXTRACTED_FROM_TEXT, "ZZ") }
    }

    private fun rejected(create: () -> Any) {
        try {
            create()
            throw AssertionError("Invalid draft accepted")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
