package com.gametimer.child

import android.content.Context
import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The parent PIN of this phone (owner's decision for C2): the child knows the phone's own PIN, so connecting a
 * parent's phone, removing one and starting over need this PIN instead. Stored as a salted PBKDF2 hash in
 * private preferences (backups are off). Wrong guesses lock it: 5 free, then 30 s doubling up to an hour,
 * counted across restarts.
 */
class ParentPin(private val context: Context) {
    private val prefs = context.getSharedPreferences("parent-pin", Context.MODE_PRIVATE)

    val isSet: Boolean get() = prefs.contains(HASH)

    /** Sets (or replaces) the PIN: 4 to 12 digits. */
    @Synchronized
    fun set(pin: String) {
        require(isValid(pin)) { "4 to 12 digits" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(SALT, hex(salt)).putString(HASH, hex(hash(pin, salt))).putInt(FAILS, 0).putLong(LOCKED_UNTIL_ELAPSED, 0)
            .putInt(BOOT, bootCount()).commit()
    }

    /**
     * Milliseconds until another try is allowed, 0 when one is. Only the monotonic clock counts, so a changed date
     * can't end the wait.
     */
    @Synchronized
    fun lockedForMillis(): Long {
        if (prefs.getInt(BOOT, -1) != bootCount()) {
            // A reboot: the monotonic clock started over and the wall clock can be moved. A lock that was still
            // running when last seen starts its step again (a reboot with a moved date gains nothing); one seen
            // over doesn't come back.
            val step = if (prefs.getBoolean(ACTIVE, false)) lockFor(prefs.getInt(FAILS, 0)) else 0L
            prefs.edit().putLong(LOCKED_UNTIL_ELAPSED, SystemClock.elapsedRealtime() + step).putInt(BOOT, bootCount()).commit()
        }
        val left = (prefs.getLong(LOCKED_UNTIL_ELAPSED, 0) - SystemClock.elapsedRealtime()).coerceIn(0, MAX_LOCK_MS)
        if ((left > 0) != prefs.getBoolean(ACTIVE, false)) prefs.edit().putBoolean(ACTIVE, left > 0).commit()
        return left
    }

    sealed interface Result {
        data object Ok : Result
        data object Wrong : Result
        data class Locked(val millis: Long) : Result
    }

    @Synchronized
    fun check(pin: String): Result {
        val wait = lockedForMillis()
        if (wait > 0) return Result.Locked(wait)
        val salt = prefs.getString(SALT, null)?.let(::unhex) ?: return Result.Wrong
        val expected = prefs.getString(HASH, null)?.let(::unhex) ?: return Result.Wrong
        if (MessageDigest.isEqual(hash(pin, salt), expected)) {
            prefs.edit().putInt(FAILS, 0).putLong(LOCKED_UNTIL_ELAPSED, 0).putBoolean(ACTIVE, false).commit()
            return Result.Ok
        }
        val fails = prefs.getInt(FAILS, 0) + 1
        val lock = lockFor(fails)
        // commit, not apply: a guess counts even if the app is killed right after
        prefs.edit().putInt(FAILS, fails).putLong(LOCKED_UNTIL_ELAPSED, SystemClock.elapsedRealtime() + lock).putInt(BOOT, bootCount())
            .putBoolean(ACTIVE, lock > 0).commit()
        return if (lock > 0) Result.Locked(lock) else Result.Wrong
    }

    private fun lockFor(fails: Int): Long =
        if (fails < FREE_TRIES) 0L else (30_000L shl (fails - FREE_TRIES).coerceAtMost(7)).coerceAtMost(MAX_LOCK_MS)

    private fun bootCount(): Int = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)).encoded

    companion object {
        private const val SALT = "salt"
        private const val HASH = "hash"
        private const val FAILS = "fails"
        private const val LOCKED_UNTIL_ELAPSED = "lockedUntilElapsed"
        private const val BOOT = "boot"
        private const val ACTIVE = "lockActive"
        private const val ITERATIONS = 120_000
        private const val FREE_TRIES = 5
        private const val MAX_LOCK_MS = 3_600_000L

        fun isValid(pin: String): Boolean = pin.length in 4..12 && pin.all { it in '0'..'9' }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}

/** "Parent mode": after the PIN, protected actions are open for 5 minutes, until the app leaves the screen. */
object ParentUnlock {
    private const val WINDOW_MS = 5 * 60_000L
    @Volatile private var until = 0L
    private val _locks = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Counts the locks: protected dialogs still open close when it changes. */
    val locks: kotlinx.coroutines.flow.StateFlow<Int> = _locks

    val isUnlocked: Boolean get() = SystemClock.elapsedRealtime() < until

    fun unlock() { until = SystemClock.elapsedRealtime() + WINDOW_MS }

    fun lock() {
        until = 0
        _locks.value++
    }
}
