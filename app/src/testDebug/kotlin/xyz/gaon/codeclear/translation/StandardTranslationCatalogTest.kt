package xyz.gaon.codeclear.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardTranslationCatalogTest {
    @Test
    fun civilActionsAndReleaseConditionsRemainDistinct() {
        val missile = "14:05 북한 미사일 발사, 서울, 부산지역 경계경보, 낙하물 유의, 야외활동 자제. MISSILES ALERT [행정안전부]"
        val preliminary = StandardTranslationCatalog.translate(missile, TranslationTarget.ENGLISH)!!
        assertEquals(
            "14:05, Seoul, Busan: North Korean missile launch. Preliminary air-raid warning. Beware of falling objects and avoid outdoor activities. MISSILES ALERT [Ministry of the Interior and Safety]",
            preliminary.text
        )
        val attack = "14:05 북한 미사일 발사, 서울, 부산지역 공습경보, 지하시설 또는 건물 내로 대피. " +
            "EVACUATION, MISSILES ATTACK [행정안전부]"
        assertEquals(
            "14:05, Seoul, Busan: North Korean missile launch. Air-raid warning. Seek shelter underground or inside a building. EVACUATION, MISSILES ATTACK [Ministry of the Interior and Safety]",
            StandardTranslationCatalog.translate(attack, TranslationTarget.ENGLISH)?.text
        )
        assertNull(
            StandardTranslationCatalog.translate(missile + " 훈련입니다.", TranslationTarget.ENGLISH)
        )
        assertNull(
            StandardTranslationCatalog.translate(
                missile.replace("서울", "광주"),
                TranslationTarget.ENGLISH
            )
        )
        assertNull(StandardTranslationCatalog.translate(missile, TranslationTarget.FRENCH))
        val release = "북한 미사일 위험범위 이탈 14:05분 서울지역 공습경보 해제, 일상으로 복귀 바랍니다. [행정안전부]"
        assertEquals(
            "North Korean missiles have left the danger area. At 14:05, the air-raid warning for Seoul was lifted. Please resume normal activities. [Ministry of the Interior and Safety]",
            StandardTranslationCatalog.translate(release, TranslationTarget.ENGLISH)?.text
        )
    }

    @Test
    fun tsunamiHasNoInventedSeverityAndRetainsTheDifferentActions() {
        val body = "2026-11-08 14:05 부산지역 지진해일 특보 발령, 선박대피, 해변주민높은지대대피Tsunami[기상청]"
        assertEquals(
            "2026-11-08 14:05: Tsunami alert issued for Busan. Evacuate vessels; coastal residents should evacuate to higher ground. Tsunami [Korea Meteorological Administration]",
            StandardTranslationCatalog.translate(body, TranslationTarget.ENGLISH)?.text
        )
        assertNull(
            StandardTranslationCatalog.translate(
                body.replace("부산", "제주"),
                TranslationTarget.ENGLISH
            )
        )
        assertTrue(
            StandardTranslationCatalog.translate(
                body.replace("부산", "제주특별자치도"),
                TranslationTarget.ENGLISH
            )!!.text.contains("Jeju-do")
        )
        val japanese = StandardTranslationCatalog.translate(
            "津波警報が一部解除されました",
            TranslationTarget.ENGLISH
        )!!
        assertEquals("Tsunami Warning partially lifted.", japanese.text)
        assertEquals(
            "JMA has cancelled the EEW.",
            StandardTranslationCatalog.translate(
                "先ほど発表した緊急地震速報を取り消します",
                TranslationTarget.ENGLISH
            )?.text
        )
        assertNull(
            StandardTranslationCatalog.translate(
                "津波警報が一部解除されましたが、避難を続けてください",
                TranslationTarget.ENGLISH
            )
        )
    }

    @Test
    fun officialFccPairPreservesKnownAgencyAndExactUrl() {
        val source = "EARTHQUAKE DETECTED! Drop, Cover, Hold On. Protect Yourself. " +
            "USGS https://example.org/a?q=1&b=2"
        assertEquals(
            "지진 감지! 엎드립니다, 머리를 보호합니다, 신체를 지지합니다. 자신을 보호하십시오. USGS https://example.org/a?q=1&b=2",
            StandardTranslationCatalog.translate(source, TranslationTarget.KOREAN)?.text
        )
        assertNull(
            StandardTranslationCatalog.translate(
                source.replace("USGS", "Unknown Sender"),
                TranslationTarget.KOREAN
            )
        )
        assertNull(
            StandardTranslationCatalog.translate(
                source.replace("https://example.org/a?q=1&b=2", "https://user:pass@example.org"),
                TranslationTarget.KOREAN
            )
        )
        assertNull(
            StandardTranslationCatalog.translate(
                "THIS IS A TEST. " + source,
                TranslationTarget.KOREAN
            )
        )
    }

    @Test
    fun everyPreparedRowIsReachableAndRejectsAppendedInstructions() {
        val values = mapOf(
            "time" to "14:05", "date" to "2026-11-08 14:05", "area" to "서울",
            "area2" to "부산", "agency" to "행정안전부", "distance" to "12", "magnitude" to "3.2",
            "intensity" to "4", "url" to "https://example.org/a"
        )
        StandardTranslationCatalog.templates.forEach { definition ->
            var body = definition.sourceText
            values.forEach { (id, value) ->
                body =
                    body.replace(
                        "{$id}",
                        if (id == "agency" &&
                            definition.jurisdiction == "US"
                        ) {
                            "USGS"
                        } else {
                            value
                        }
                    )
            }
            definition.targets.keys.forEach { tag ->
                val target = TranslationTarget.entries.single { it.languageTag == tag }
                val result = StandardTranslationCatalog.translate(body, target)
                assertNotNull(definition.id, result)
                assertEquals(definition.id, result!!.attribution.templateId)
                assertFalse(result.text.contains('{'))
                assertNull(
                    definition.id,
                    StandardTranslationCatalog.translate(body + " 추가 조건: 대피하지 마세요.", target)
                )
            }
            assertNotNull(
                StandardTranslationCatalog.references.singleOrNull {
                    it.id ==
                        definition.referenceId
                }
            )
        }
    }

    @Test
    fun duplicateAliasesAndMissingTargetNamesFailClosed() {
        fun entry(id: String) = StandardNameCatalog.Entry(
            id,
            "KR",
            StandardSlotKind.AREA,
            "ko",
            id,
            setOf("중구"),
            mapOf("en" to id),
            "test"
        )
        val catalog = StandardNameCatalog(listOf(entry("서울 중구"), entry("부산 중구")))
        assertNull(catalog.resolve("KR", StandardSlotKind.AREA, "중구", "en"))
        assertEquals("서울 중구", catalog.resolve("KR", StandardSlotKind.AREA, "서울 중구", "en"))
        assertNull(catalog.resolve("US", StandardSlotKind.AREA, "서울 중구", "en"))
        assertNull(catalog.resolve("KR", StandardSlotKind.AREA, "서울 중구", "ja"))
    }
}
