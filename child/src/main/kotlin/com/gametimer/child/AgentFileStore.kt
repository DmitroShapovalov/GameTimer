package com.gametimer.child

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.gametimer.protocol.agent.AgentStateData
import com.gametimer.protocol.agent.AgentStore
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

/**
 * The agent's protocol state (its X25519 private key, the paired parents with their `ak`, the rid cache) in
 * noBackupFilesDir, sealed with AES-256-GCM under a non-exportable Android Keystore key (backups are off).
 *
 * Contract of [AgentStore.load]: null only when nothing was ever stored. A file that exists but can't be
 * opened throws [StoreUnreadableException]: the agent then doesn't start, so it never replaces the
 * pairings by itself; the parent decides on the phone (PIN) whether to start over ([wipe]).
 */
class AgentFileStore(context: Context) : AgentStore {
    private val file = File(context.noBackupFilesDir, "agent-state.bin")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun load(): AgentStateData? {
        if (!file.exists()) return null
        try {
            val blob = file.readBytes()
            val cipher = Cipher.getInstance(AES)
            cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: throw IOException("the Keystore key is gone"), GCMParameterSpec(128, blob, 0, 12))
            val plain = cipher.doFinal(blob, 12, blob.size - 12)
            return json.decodeFromString(AgentStateData.serializer(), plain.decodeToString()).also { plain.fill(0) }
        } catch (e: Exception) {
            throw StoreUnreadableException(e)
        }
    }

    @Synchronized
    override fun save(state: AgentStateData) {
        val plain = json.encodeToString(AgentStateData.serializer(), state).encodeToByteArray()
        val cipher = Cipher.getInstance(AES)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: newKey())
        val sealed = cipher.iv + cipher.doFinal(plain)
        plain.fill(0)
        val tmp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(sealed)
            out.fd.sync() // on disk before the rename: no truncated file after a power loss
        }
        if (!tmp.renameTo(file)) throw IOException("cannot replace ${file.name}")
    }

    /** Starts over: a new identity on the next start, every parent must pair again. Behind the parent PIN. */
    @Synchronized
    fun wipe() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
    }

    private fun existingKey(): SecretKey? =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as? SecretKey

    private fun newKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "gametimer-agent-state"
        const val AES = "AES/GCM/NoPadding"
    }
}

class StoreUnreadableException(cause: Throwable) : Exception("the agent's state can't be read", cause)
