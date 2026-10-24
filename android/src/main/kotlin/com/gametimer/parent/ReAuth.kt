package com.gametimer.parent

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import android.os.Build
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Re-authentication with the phone's own lock (fingerprint / face / PIN, protocol §12): before admin
 * actions (roles, removing another parent), and when the app was last unlocked more than
 * [UNLOCK_MAX_AGE_MS] ago. Daily controls never ask. The point is a child holding the parent's
 * already-unlocked phone, not a stolen phone (that's the phone's own lock screen).
 */
class ReAuth(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("reauth", Context.MODE_PRIVATE)
    @Volatile private var lastAuthElapsed: Long? = null

    /** True when the app must be unlocked before showing anything (first start counts as unlocked). */
    fun needsUnlock(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val last = prefs.getLong(KEY_UNLOCKED_AT, -1)
        if (last < 0) { prefs.edit().putLong(KEY_UNLOCKED_AT, nowMillis).apply(); return false } // not an authentication
        // A clock set back can't extend the window: "in the future" counts as expired too
        return nowMillis - last !in 0..UNLOCK_MAX_AGE_MS
    }

    fun markUnlocked(nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_UNLOCKED_AT, nowMillis).apply()
        lastAuthElapsed = SystemClock.elapsedRealtime()
    }

    /** An admin action may skip the prompt right after another one (no double prompts). */
    fun recentlyAuthenticated(): Boolean {
        val last = lastAuthElapsed ?: return false
        return SystemClock.elapsedRealtime() - last in 0..ADMIN_GRACE_MS
    }

    /**
     * Shows the system prompt and returns true on success. A phone without any screen lock can't be
     * asked: then this returns true (there is nothing to check against) — [hasScreenLock] tells the UI.
     */
    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String): Boolean {
        if (!hasScreenLock(activity)) return true
        if (inProgress) return false // a double tap: the prompt is already up
        inProgress = true
        // A prompt that never answers (e.g. shown while the activity was being saved) can't block later ones
        val ok = try { withTimeoutOrNull(PROMPT_TIMEOUT_MS) { prompt(activity, title, subtitle) } ?: false } finally { inProgress = false }
        if (ok) markUnlocked()
        return ok
    }

    /** A prompt is showing (main thread). */
    @Volatile var inProgress: Boolean = false
        private set

    private suspend fun prompt(activity: FragmentActivity, title: String, subtitle: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(false) // cancelled, locked out, …
                    }
                    // onAuthenticationFailed: a wrong finger; the prompt stays open and tries again
                })
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build(),
            )
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }

    /** A PIN / pattern / password is set (then the prompt can always fall back to it), or a biometric is enrolled. */
    fun hasScreenLock(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (keyguard?.isDeviceSecure == true) return true
        return BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    companion object {
        // Class 3 ("strong") biometrics or the PIN; Android 9–10 can't combine strong with the PIN
        private val AUTHENTICATORS =
            (if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG else BIOMETRIC_WEAK) or DEVICE_CREDENTIAL
        const val UNLOCK_MAX_AGE_MS: Long = 14L * 24 * 60 * 60 * 1000
        const val ADMIN_GRACE_MS: Long = 60_000
        private const val PROMPT_TIMEOUT_MS: Long = 120_000
        private const val KEY_UNLOCKED_AT = "unlockedAt"
    }
}
