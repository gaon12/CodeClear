package xyz.gaon.codeclear.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.gaon.codeclear.reception.CountryEvidence
import xyz.gaon.codeclear.reception.ReceptionCountry

class NationalAlertCategoriesTest {
    private fun capture(category: Int) = CapturedAlert(
        "test.system.receiver",
        CaptureSource.ORIGINAL_NOTIFICATION,
        "Unrelated localized heading",
        "[DRILL] Text",
        1700000000000,
        broadcast = BroadcastMetadata(1, 42, category, 3, 1, null),
        context = ReceptionContext(ReceptionCountry("KR", CountryEvidence.CBS_PLMN))
    )

    @Test
    fun reviewedKoreanCategoriesAndLanguageVariantsRemainDistinct() {
        val expected = mapOf(
            4370 to NationalAlertType.EXTREME_EMERGENCY,
            4383 to NationalAlertType.EXTREME_EMERGENCY,
            4371 to NationalAlertType.EMERGENCY,
            4384 to NationalAlertType.EMERGENCY,
            4372 to NationalAlertType.PUBLIC_SAFETY,
            4385 to NationalAlertType.PUBLIC_SAFETY,
            4379 to NationalAlertType.MISSING_PERSON,
            4392 to NationalAlertType.MISSING_PERSON
        )
        expected.forEach { (category, type) ->
            assertEquals(type, nationalAlertType(capture(category)))
            assertEquals(type, nationalAlertType(capture(category).copy(title = "다른 언어 제목")))
        }
    }

    @Test
    fun unverifiedCountryPeersOtherFormatsAndReservedChannelsStayUnclassified() {
        val original = capture(4372)
        assertNull(nationalAlertType(original.copy(source = CaptureSource.PEER)))
        assertNull(
            nationalAlertType(
                original.copy(
                    context = ReceptionContext(ReceptionCountry("US", CountryEvidence.CBS_PLMN))
                )
            )
        )
        assertNull(
            nationalAlertType(
                original.copy(
                    context = ReceptionContext(
                        ReceptionCountry("KR", CountryEvidence.NETWORK_SNAPSHOT)
                    )
                )
            )
        )
        assertNull(
            nationalAlertType(original.copy(broadcast = original.broadcast!!.copy(format = 2)))
        )
        assertNull(nationalAlertType(capture(4373)))
        assertNull(nationalAlertType(original.copy(broadcast = null)))
    }
}
