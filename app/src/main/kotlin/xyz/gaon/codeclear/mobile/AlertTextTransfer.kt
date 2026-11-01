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
        validate(body)
        return Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
    }

    private fun validate(body: String) {
        require(body.length in 1..16_384 && body.isNotBlank())
    }
}
