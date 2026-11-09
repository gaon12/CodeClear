package xyz.gaon.codeclear.translation

import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface ServerTokenStorage {
    fun save(value: String)
    fun load(): ServerAccessToken?
    fun loadOrCreateInstallation(): ServerAccessToken? = load()
    fun clear()
}

/** Encrypt one access token with a non-exportable platform key, outside backup and saved UI state. */
internal class ServerCredentialStore(
    context: Context,
    private val scope: String,
    name: String = "server_access"
) : ServerTokenStorage {
    init {
        require(scope.matches(Regex("[0-9a-f]{64}")))
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,39}")))
    }

    internal val path = File(context.noBackupFilesDir, "$name.bin")
    private val file = AtomicFile(path)
    private val alias = "codeclear.$name"

    @Synchronized
    override fun loadOrCreateInstallation(): ServerAccessToken {
        checkWorker()
        load()?.let { return it }
        // Corruption or a changed route must not silently abandon an existing private cache.
        check(!path.exists()) { "Private installation storage is unavailable" }
        val created = ServerAccessToken.newInstallation()
        val value = created.bytes()
        try {
            save(value.toString(Charsets.US_ASCII))
        } finally {
            value.fill(0)
        }
        return requireNotNull(load())
    }

    @Synchronized
    override fun save(value: String) {
        checkWorker()
        val token = requireNotNull(ServerAccessToken.parse(value))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(scope.toByteArray(Charsets.US_ASCII))
        require(cipher.iv.size == 12)
        val plain = token.bytes()
        val encrypted = try {
            cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
        val output = file.startWrite()
        try {
            output.write(byteArrayOf(1))
            output.write(cipher.iv)
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized
    override fun load(): ServerAccessToken? {
        checkWorker()
        return try {
            val bytes = file.openRead().use { input ->
                val buffer = ByteArray(286)
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size !in 61..285) return null
                buffer.copyOf(size)
            }
            if (bytes[0] != 1.toByte()) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(create = false),
                GCMParameterSpec(128, bytes, 1, 12)
            )
            cipher.updateAAD(scope.toByteArray(Charsets.US_ASCII))
            val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
            try {
                ServerAccessToken.parse(plain.toString(Charsets.US_ASCII))
            } finally {
                plain.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun clear() {
        checkWorker()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.deleteEntry(alias)
        file.delete()
        check(!path.exists())
    }

    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keys.containsAlias(alias)) return keys.getKey(alias, null) as SecretKey
        check(create)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build()
        )
        return generator.generateKey()
    }

    private fun checkWorker() = check(Looper.myLooper() != Looper.getMainLooper())
}
