package xyz.gaon.codeclear.probe

import android.os.Build
import android.os.Parcel
import android.os.Parcelable

data class ProbeBroadcastFields(
    val format: Int,
    val serial: Int,
    val category: Int,
    val priority: Int,
    val cmasClass: Int?,
    val etwsType: Int?,
    val receivedAtMillis: Long
)

data class ProbeDecodedBroadcast(val body: String, val fields: ProbeBroadcastFields)

/**
 * Experimental data-only adapter for two reviewed framework Parcel layouts.
 * Parcelable is public; the source message's binary schema is not a stable public contract.
 * Unknown layouts fail closed. Never call hidden getters, retain location, or guess offsets.
 * Source: AOSP frameworks/base/telephony/java/android/telephony/SmsCbMessage.java.
 * The baseline and nested warning/location writers were reviewed for Android 11 through 16;
 * accepting a known layout does not establish capture support for an untested source or firmware.
 * The extended CMAS fields were reviewed in the connected API 37 framework's
 * SmsCbCmasInfo.writeToParcel. Selection uses complete data layouts, not manufacturer names.
 */
object ProbeBroadcastPayload {
    fun decode(message: Parcelable): ProbeDecodedBroadcast? {
        if (Build.VERSION.SDK_INT !in 30..37 ||
            message.javaClass.name != "android.telephony.SmsCbMessage"
        ) {
            return null
        }
        val parcel = Parcel.obtain()
        return try {
            message.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            readForPlatform(parcel, Build.VERSION.SDK_INT)
        } catch (_: RuntimeException) {
            null
        } finally {
            parcel.recycle()
        }
    }

    internal fun readForPlatform(parcel: Parcel, sdk: Int): ProbeDecodedBroadcast? {
        if (sdk !in 30..37) return null
        val baseline = readReviewedParcel(parcel)
        if (sdk != 37) return baseline
        parcel.setDataPosition(0)
        val extended = readReviewedParcel(parcel, extendedCmas = true)
        // Accept only one distinct result across the complete reviewed schemas.
        return listOfNotNull(baseline, extended).distinct().singleOrNull()
    }

    internal fun readReviewedParcel(
        parcel: Parcel,
        extendedCmas: Boolean = false
    ): ProbeDecodedBroadcast? {
        return try {
            require(parcel.dataSize() in 64..65536 && parcel.dataPosition() == 0)
            fun number(): Int {
                require(parcel.dataAvail() >= 4)
                return parcel.readInt()
            }
            fun text(limit: Int): String? {
                require(parcel.dataAvail() >= 4)
                return parcel.readString()?.also { require(it.length <= limit) }
            }
            val format = number().also { require(it in 1..2) }
            number().also { require(it in 0..3) }
            val serial = number().also { require(it in 0..65535) }
            text(16) // PLMN is deliberately discarded with the following area/cell identifiers.
            number()
            number()
            val category = number().also { require(it in 0..65535) }
            text(32)
            number().also { require(it in 0..255) }
            val body = text(8192)?.also { require(it.isNotBlank()) } ?: return null
            val priority = number().also { require(it in 0..3) }
            var cmas: Int? = null
            var etws: Int? = null
            when (number()) {
                'C'.code -> {
                    cmas = number().also { require(it in -1..8) }
                    repeat(5) { number().also { value -> require(value in -1..255) } }
                    if (extendedCmas) {
                        // Reviewed extension: message ID, language, handling, expiry, record type.
                        // These fields are discarded; they do not redefine the standard priority.
                        repeat(3) { number() }
                        require(parcel.dataAvail() >= 8)
                        parcel.readLong()
                        number()
                    }
                }

                'E'.code -> {
                    etws = number().also { require(it in -1..4) }
                    repeat(3) { number().also { value -> require(value in 0..1) } }
                    require(parcel.dataAvail() >= 4)
                    val sizePosition = parcel.dataPosition()
                    val size = number()
                    require(size in -1..256 && size <= parcel.dataAvail())
                    parcel.setDataPosition(sizePosition)
                    parcel.createByteArray() // Security bytes are neither retained nor interpreted.
                }

                '0'.code -> Unit

                else -> return null
            }
            require(parcel.dataAvail() >= 8)
            val received = parcel.readLong().also { require(it > 0) }
            text(32768) // Geofencing coordinates are not part of the translation input.
            number()
            number()
            number()
            require(parcel.dataAvail() == 0)
            ProbeDecodedBroadcast(
                body,
                ProbeBroadcastFields(format, serial, category, priority, cmas, etws, received)
            )
        } catch (_: RuntimeException) {
            null
        }
    }
}
