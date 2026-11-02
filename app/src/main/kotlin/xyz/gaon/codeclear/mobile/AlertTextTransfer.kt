package xyz.gaon.codeclear.mobile

import android.content.ClipData
import android.content.Intent
import android.os.PersistableBundle

internal object AlertTextTransfer {
    fun clip(label: String, body: String): ClipData {
        validate(body)
        return ClipData.newPlainText(label, body).apply {
            description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
    }

    fun share(body: String): Intent {
        validate(body, limit = 32_768)
        return Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
    }

    private fun validate(body: String, limit: Int = 16_384) {
        require(body.length in 1..limit && body.isNotBlank())
    }
}
