package xyz.gaon.codeclear.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssuerEstimatesTest {
    @Test fun municipalAndAgencySignaturesSupportPairedBracketsAndHeaderGroups() {
        val signatures = listOf(
            "[강남구청]" to "강남구청", "【경주시】" to "경주시",
            "［경기도］" to "경기도", "「横浜市」" to "横浜市",
            "〔臺北市政府〕" to "臺北市政府",
            "(부산광역시 소방재난본부)" to "부산광역시 소방재난본부",
            "（해양경찰청）" to "해양경찰청", "[서울시 강남구청]" to "서울시 강남구청",
            "[Example County Police]" to "Example County Police"
        )
        for ((signature, name) in signatures) {
            val original = "[DRILL] $signature Gate 3. Call 112."
            val claim = estimateIssuer(original)
            assertEquals(original, name, claim?.originalName)
            if (name != null) assertEquals(signature, claim?.sourceExcerpt)
        }
        assertEquals("경주시", estimateIssuer("[DRILL] Gate 3. 【경주시】 [훈련]")?.originalName)
        assertEquals("nws", estimateIssuer("[nws] [DRILL] Gate 3.")?.originalName)
    }

    @Test fun explicitSenderFieldsAcceptLiteralNamesInSupportedScripts() {
        for ((label, name) in listOf(
            "발신기관" to "강남구청", "발신 주체" to "기관 X", "발송기관" to "경주시",
            "Sender" to "Test Office", "Issued by" to "Agency X",
            "発信元" to "横浜市", "發布單位" to "臺北市政府", "发布单位" to "测试机构",
            "Émetteur" to "Préfecture exemple", "Absender" to "Stadt Beispiel"
        )) {
            val line = "$label： $name"
            val original = "[DRILL] Gate 3.\n$line\nCall 112."
            val claim = estimateIssuer(original)!!
            assertEquals(name, claim.originalName)
            assertEquals(line, claim.sourceExcerpt)
            assertEquals(IssuerBasis.TEXT_PATTERN_ESTIMATE, claim.basis)
            assertNull(claim.countryIso)
            assertNull(claim.translatedName)
        }
        assertEquals("강남구청", estimateIssuer("[DRILL]\n발신기관: [강남구청]")?.originalName)
    }

    @Test fun conflictingExplicitClaimsStayUnknownWhileRepeatedNamesAgree() {
        for (body in listOf(
            "[강남구청] [DRILL] [경주시]",
            "[강남구청] [DRILL]\n발신기관: 경주시",
            "[DRILL]\n발신기관: 강남구청\nSender: Test Office",
            "[강남구청] text [경주시] more text"
        )) {
            assertNull(body, estimateIssuer(body))
        }
        assertEquals(
            "강남구청",
            estimateIssuer("[강남구청] [DRILL] [강남구청]\n발신기관: 강남구청")?.originalName
        )
    }

    @Test fun proseLocationsLinksAndMismatchedDelimitersAreNotSenderEvidence() {
        for (body in listOf(
            "[DRILL] 서울시 강남구에서 Gate 3로 이동하세요.",
            "[DRILL] Gate 3 in 横浜市.", "[DRILL] text 【경주시】 more text",
            "[긴급재난문자] [DRILL] Gate 3.", "[강남구청】 [DRILL]",
            "[[강남구청]] [DRILL]", "[DRILL]\n발신기관: https://example.invalid",
            "[DRILL]\nSender: test@example.invalid", "[DRILL]\n발신기관: 112",
            "[DRILL]\nSender: <script>alert(1)</script>"
        )) {
            assertNull(body, estimateIssuer(body))
        }
    }

    @Test fun noisyOrUnsafeSourceWorkIsBoundedAndNeverCreatesEvidence() {
        for (body in listOf(
            "[강남구청]" + "[훈련]".repeat(33),
            "발신기관: 강남구청\n".repeat(33),
            "[".repeat(8192),
            "【" + "가".repeat(8190) + "】",
            "[강남구청] \u2066[DRILL]",
            "[DRILL]\nSender: Agency\u0000X",
            "[DRILL]\nSender: " + "X".repeat(129)
        )) {
            assertNull(body.take(32), estimateIssuer(body))
        }
    }

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
