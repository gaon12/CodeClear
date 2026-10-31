package xyz.gaon.codeclear.reception

import android.content.Context
import android.os.Looper
import android.telephony.TelephonyManager

/** Snapshot only: no SIM identity, phone number, location, or privileged phone permission is read. */
class NetworkCountry(private val context: Context) {
    fun read(): ReceptionCountry? {
        check(Looper.myLooper() != Looper.getMainLooper())
        return try {
            normalizeCountry(
                context.getSystemService(TelephonyManager::class.java)?.networkCountryIso
            )
                ?.let { ReceptionCountry(it, CountryEvidence.NETWORK_SNAPSHOT) }
        } catch (_: SecurityException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
    }
}
