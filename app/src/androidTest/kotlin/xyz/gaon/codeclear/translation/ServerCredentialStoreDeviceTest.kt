package xyz.gaon.codeclear.translation

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ServerCredentialStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = serverDigest("https://example.invalid/api.php")
    private val token = "synthetic-client-token-aaaaaaaaaaaaaaaa"

    @Test
    fun tokenSurvivesReloadWithoutPlaintextInStorageAndClearingRemovesIt() {
        val store = ServerCredentialStore(context, scope, "fixtureServerToken1")
        try {
            store.save(token)
            assertFalse(store.path.readBytes().toString(Charsets.ISO_8859_1).contains(token))
            val restored = ServerCredentialStore(context, scope, "fixtureServerToken1").load()
            assertEquals(serverDigest(token), restored?.fingerprint)
            assertEquals("Bearer $token", restored?.authorization())
            store.clear()
            assertNull(store.load())
            assertFalse(store.path.exists())
        } finally {
            store.clear()
        }
    }

    @Test
    fun alteredCiphertextAndAnotherEndpointCannotReuseTheCredential() {
        val store = ServerCredentialStore(context, scope, "fixtureServerToken2")
        try {
            store.save(token)
            val other =
                ServerCredentialStore(
                    context,
                    serverDigest("https://other.invalid/api.php"),
                    "fixtureServerToken2"
                )
            assertNull(other.load())
            val bytes = store.path.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            store.path.writeBytes(bytes)
            assertNull(store.load())
            store.path.writeBytes(ByteArray(1024))
            assertNull(store.load())
        } finally {
            store.clear()
        }
    }

    @Test
    fun invalidCredentialsNeverReplaceTheSavedToken() {
        val store = ServerCredentialStore(context, scope, "fixtureServerToken3")
        try {
            store.save(token)
            for (invalid in listOf(
                "",
                "x".repeat(31),
                "x".repeat(257),
                "$token\r\nInjected",
                "$token+"
            )) {
                var rejected = false
                try {
                    store.save(invalid)
                } catch (_: IllegalArgumentException) {
                    rejected = true
                }
                assertEquals(true, rejected)
                assertEquals(serverDigest(token), store.load()?.fingerprint)
            }
        } finally {
            store.clear()
        }
    }
}
