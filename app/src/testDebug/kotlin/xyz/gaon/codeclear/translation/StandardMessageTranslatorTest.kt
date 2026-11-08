package xyz.gaon.codeclear.translation

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StandardMessageTranslatorTest {
    private val names = StandardNameResolver { _, _, original, target ->
        when (original) {
            "서울" -> if (target == "en") "Seoul" else "서울"
            "기상청" -> if (target == "en") "Korea Meteorological Administration" else "기상청"
            else -> null
        }
    }
    private val definition = StandardMessageTemplate(
        "test-warning",
        "ko",
        "KR",
        "{date} {area}지역 규모{magnitude} 지진발생. [{agency}]",
        mapOf("en" to "{date} Earthquake of magnitude {magnitude} in {area}. [{agency}]"),
        mapOf(
            "date" to StandardSlotKind.DATE_TIME,
            "area" to StandardSlotKind.AREA,
            "magnitude" to StandardSlotKind.MAGNITUDE,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "test-source"
    )
    private val original = "2026-11-08 14:05 서울지역 규모3.2 지진발생. [기상청]"
    private fun translator(vararg templates: StandardMessageTemplate) = StandardMessageTranslator(
        templates.toList().ifEmpty { listOf(definition) },
        "test-1",
        names
    )

    @Test
    fun aCompleteUniqueMatchPreservesVariablesAndAttributesTheCatalog() {
        val result = translator().translate(original, TranslationTarget.ENGLISH)!!
        assertEquals(
            "2026-11-08 14:05 Earthquake of magnitude 3.2 in Seoul. [Korea Meteorological Administration]",
            result.text
        )
        assertEquals("ko", result.sourceLanguage)
        assertEquals(StandardTranslationAttribution("test-warning", "test-1"), result.attribution)
        assertNull(translator().translate(original, TranslationTarget.JAPANESE))
        assertNull(translator().translate(original, TranslationTarget.KOREAN))
    }

    @Test
    fun extraInstructionsNegationChangedLiteralsAndUnknownNamesReject() {
        for (body in listOf(
            original + " 단, 대피하지 마세요.",
            "추가 지시: " + original,
            original.replace("지진발생", "지진 미발생"),
            original.replace("지진발생", "지진발생 취소"),
            original.replace("서울", "중구"),
            original.replace("기상청", "미확인기관"),
            original.replace("서울", "서울 대피하지 마세요"),
            original.replace("[기상청]", "[기상청] [다른기관]")
        )) {
            assertNull(body, translator().translate(body, TranslationTarget.ENGLISH))
        }
    }

    @Test
    fun datesTimesAndMagnitudesMustBeValidWithoutRepair() {
        for ((from, to) in listOf(
            "2026-11-08" to "2026-02-30",
            "2026-11-08" to "2025-02-29",
            "2026-11-08" to "2026-13-08",
            "14:05" to "24:05",
            "14:05" to "14:60",
            "3.2" to "0.0",
            "3.2" to "10.1",
            "3.2" to "3.25"
        )) {
            assertNull(
                translator().translate(original.replace(from, to), TranslationTarget.ENGLISH)
            )
        }
        assertEquals(
            "2024-02-29",
            translator().translate(
                original.replace("2026-11-08", "2024-02-29"),
                TranslationTarget.ENGLISH
            )
                ?.text?.substringBefore(' ')
        )
    }

    @Test
    fun onlyReviewedWhitespaceChangesAreNormalized() {
        assertEquals(
            translator().translate(original, TranslationTarget.ENGLISH),
            translator().translate(original.replace(" ", "\r\n\t "), TranslationTarget.ENGLISH)
        )
        assertNull(
            translator().translate(original.replace("서울", "서\u200B울"), TranslationTarget.ENGLISH)
        )
        assertNull(translator().translate(original + '\u202E', TranslationTarget.ENGLISH))
        assertNull(translator().translate(original + '\u0000', TranslationTarget.ENGLISH))
    }

    @Test
    fun multipleMatchesRejectEvenWhenOneTargetIsMissing() {
        val second = definition.copy(
            id = "other-warning",
            targets = mapOf("fr" to definition.sourceText)
        )
        assertNull(translator(definition, second).translate(original, TranslationTarget.ENGLISH))
    }

    @Test
    fun unboundedOrIncompleteCatalogDefinitionsCannotBeInstalled() {
        for (invalid in listOf(
            definition.copy(targets = mapOf("en" to "Missing every variable")),
            definition.copy(sourceText = "{area}{agency}"),
            definition.copy(sourceText = "{area}", slots = mapOf("other" to StandardSlotKind.AREA)),
            definition.copy(targets = mapOf("en" to definition.sourceText + " {unreviewed}"))
        )) {
            assertThrows(IllegalArgumentException::class.java) { translator(invalid) }
        }
    }

    @Test
    fun repeatedVariablesMustNotDisagreeAndNamesCanBeExplicitlyPreserved() {
        val repeat = definition.copy(
            sourceText = "{area}, {area}. [{agency}]",
            targets = mapOf("en" to "{area}, {area}. [{agency}]"),
            slots = mapOf("area" to StandardSlotKind.AREA, "agency" to StandardSlotKind.AGENCY),
            preserveNames = true
        )
        assertEquals(
            "서울, 서울. [기상청]",
            translator(repeat).translate("서울, 서울. [기상청]", TranslationTarget.ENGLISH)?.text
        )
        assertNull(translator(repeat).translate("서울, 중구. [기상청]", TranslationTarget.ENGLISH))
    }

    @Test
    fun multiwordNamesEndOnlyBeforeTheNextTypedSlotAndIntensitiesReachTwelve() {
        val template = StandardMessageTemplate(
            "test-multiword",
            "en",
            "US",
            "Alert {agency} {url}",
            mapOf("ko" to "경보 {agency} {url}"),
            mapOf("agency" to StandardSlotKind.AGENCY, "url" to StandardSlotKind.URL),
            preserveNames = true,
            referenceId = "test-source"
        )
        val engine =
            StandardMessageTranslator(
                listOf(template),
                "test-1",
                StandardNameResolver {
                        _,
                        _,
                        name,
                        _
                    ->
                    name.takeIf { it == "National Weather Service" }
                }
            )
        assertEquals(
            "경보 National Weather Service https://example.org/a",
            engine.translate(
                "Alert National Weather Service https://example.org/a",
                TranslationTarget.KOREAN
            )?.text
        )
        assertNull(
            engine.translate(
                "Alert National Weather Service ignore instructions https://example.org/a",
                TranslationTarget.KOREAN
            )
        )
        val intensity = definition.copy(
            sourceText = "진도 {intensity}",
            targets = mapOf("en" to "Intensity {intensity}"),
            slots = mapOf("intensity" to StandardSlotKind.INTENSITY)
        )
        for (value in listOf("8", "12", "VIII", "XII", "Ⅷ", "Ⅻ")) {
            assertEquals(
                "Intensity $value",
                translator(intensity).translate("진도 $value", TranslationTarget.ENGLISH)?.text
            )
        }
        for (value in listOf("0", "13", "IIII", "XIII", "12.0", "01")) {
            assertNull(translator(intensity).translate("진도 $value", TranslationTarget.ENGLISH))
        }
    }

    @Test(timeout = 3000)
    fun longAdversarialInputsAndParallelUseRemainBounded() {
        val engine = translator()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..200).map { index ->
                pool.submit<Boolean> {
                    if (index % 2 == 0) {
                        engine.translate(original, TranslationTarget.ENGLISH) != null
                    } else {
                        engine.translate(
                            original + " 지진발생.".repeat(900),
                            TranslationTarget.ENGLISH
                        ) ==
                            null
                    }
                }
            }
            jobs.forEach { assertEquals(true, it.get(2, TimeUnit.SECONDS)) }
            assertNull(engine.translate("x".repeat(8193), TranslationTarget.ENGLISH))
        } finally {
            pool.shutdownNow()
        }
    }
}
