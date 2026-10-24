package com.gametimer.parent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.gametimer.protocol.hub.PairedAgent
import com.gametimer.protocol.noise.KeyPair
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * This phone's hub identity and its paired PCs (protocol §4.4, §8.2 step 8), all in noBackupFilesDir
 * (and backups are off): nothing leaves the device.
 *
 * The X25519 private key is stored encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (non-exportable, hardware-backed where available). The Keystore can't hold X25519 keys for our
 * libsodium-based Noise directly, so it wraps the key instead. Writers are serialised.
 */
class HubStore(context: Context) {
    private val dir = context.noBackupFilesDir
    private val keyFile = File(dir, "hub-key.bin")
    private val agentsFile = File(dir, "agents.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _agents = MutableStateFlow(loadAgents())

    val agents: StateFlow<List<PairedAgent>> = _agents.asStateFlow()

    /**
     * True when the stored key could no longer be unwrapped (the Keystore key was invalidated, e.g. after
     * a factory reset of the lock screen): a new identity was created and the PC list cleared, so the
     * app can tell the user to pair again.
     */
    var identityWasReset: Boolean = false
        private set

    /** Loads (or creates) the hub key pair. Blocking Keystore and file work: call off the main thread. */
    fun loadHubKey(): KeyPair {
        val wrap = wrappingKey()
        if (keyFile.exists()) {
            try {
                val blob = keyFile.readBytes()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, wrap, GCMParameterSpec(128, blob, 0, 12))
                val priv = cipher.doFinal(blob, 12, blob.size - 12)
                return KeyPair.fromPrivateKey(priv).also { priv.fill(0) }
            } catch (e: KeyPermanentlyInvalidatedException) {
                resetIdentity()
            } catch (e: AEADBadTagException) {
                resetIdentity() // wrapped with a key that no longer exists
            } catch (e: IllegalArgumentException) {
                resetIdentity() // truncated / empty file (e.g. power loss during the first write)
            } catch (e: java.security.GeneralSecurityException) {
                if (keyFile.length() < 12 + 16 + 32) resetIdentity() else throw e
            }
            // Anything else (a transient Keystore error) propagates: never replace the identity for it
        }
        val key = KeyPair.generate()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrap)
        val priv = key.privateKey
        val sealed = cipher.iv + cipher.doFinal(priv)
        priv.fill(0)
        atomicWrite(keyFile, sealed)
        return key
    }

    /** Blocking file write: call off the main thread. */
    @Synchronized
    fun save(agent: PairedAgent) = write(_agents.value.filter { it.agentId != agent.agentId } + agent)

    /** Blocking file write: call off the main thread. */
    @Synchronized
    fun remove(agentId: String) = write(_agents.value.filter { it.agentId != agentId })

    @Synchronized
    private fun resetIdentity() {
        identityWasReset = true
        write(emptyList()) // the PCs know the old key only: they must be paired again
    }

    private fun write(list: List<PairedAgent>) {
        atomicWrite(agentsFile, json.encodeToString(ListSerializer(PairedAgent.serializer()), list).encodeToByteArray())
        _agents.value = list
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(dir, target.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync() // on disk before the rename: no truncated file after a power loss
        }
        if (!tmp.renameTo(target)) throw IOException("cannot replace ${target.name}")
    }

    private fun loadAgents(): List<PairedAgent> =
        try {
            if (agentsFile.exists()) json.decodeFromString(ListSerializer(PairedAgent.serializer()), agentsFile.readText()) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
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
        const val ALIAS = "gametimer-hub-key-wrap"
    }
}
