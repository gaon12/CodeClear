package xyz.gaon.codeclear.probe

import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationResponse
import android.view.translation.TranslationResponseValue
import androidx.annotation.RequiresApi

@RequiresApi(31)
internal object SystemTranslationProtocol {
    fun request(body: String): TranslationRequest {
        require(body.isNotBlank() && body.length <= 8192)
        return TranslationRequest.Builder()
            .setTranslationRequestValues(listOf(TranslationRequestValue.forText(body))).build()
    }

    fun text(response: TranslationResponse): String? {
        if (!response.isFinalResponse ||
            response.translationStatus != TranslationResponse.TRANSLATION_STATUS_SUCCESS
        ) {
            return null
        }
        val values = response.translationResponseValues
        if (values.size() != 1 || values.keyAt(0) != 0) return null
        val value = values.valueAt(0)
        if (value.statusCode != TranslationResponseValue.STATUS_SUCCESS) return null
        val text = value.text ?: return null
        return text.takeIf { it.length in 1..16384 && it.isNotBlank() }?.toString()
    }
}
