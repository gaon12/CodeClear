package xyz.gaon.codeclear.mobile

import org.json.JSONObject
import xyz.gaon.codeclear.reception.CountryEvidence
import xyz.gaon.codeclear.reception.ReceptionCountry

data class ReceptionContext(val country: ReceptionCountry? = null) {
    fun encode(): String? = country?.let {
        JSONObject().put(
            "version",
            1
        ).put("country", it.iso).put("evidence", it.evidence.name).toString()
    }

    companion object {
        fun decode(value: String?): ReceptionContext {
            if (value == null || value.length > 8192) return ReceptionContext()
            return try {
                val json = JSONObject(value)
                require(json.getInt("version") == 1)
                ReceptionContext(
                    ReceptionCountry(
                        json.getString("country"),
                        CountryEvidence.valueOf(json.getString("evidence"))
                    )
                )
            } catch (_: Exception) {
                ReceptionContext()
            }
        }
    }
}
