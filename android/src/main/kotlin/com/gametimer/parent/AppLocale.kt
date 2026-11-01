package com.gametimer.parent

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's own language, chosen in the app settings (like the language selector on the PC).
 *
 * Android 13+ keeps it as the per-app language ([LocaleManager]): the system applies it to every
 * activity and service and shows it in Android's settings too (res/xml/locales_config.xml).
 * Older Android versions have no such thing, so the choice is stored here, and the two contexts the app
 * shows text from are wrapped: the application ([localizedApp]: notifications, the services' ongoing
 * notifications) and the activity ([wrap] in its attachBaseContext).
 */
object AppLocale {
    /** "" is the phone's system language; the rest are the languages the app is translated into. */
    val choices = listOf("", "en", "ru", "nl", "uk")

    /** The languages under their own names, as in the selector on the PC. */
    val names = mapOf("en" to "English", "ru" to "Русский", "nl" to "Nederlands", "uk" to "Українська")

    private const val PREFS = "app"
    private const val KEY = "language"

    // Android 12 and older: the stored choice, read once (attachBaseContext runs before anything else)
    @Volatile private var stored: String? = null

    private fun legacy(context: Context): String =
        stored ?: (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: "")
            .let { if (it in choices) it else "" }.also { stored = it }

    /** The current choice: "" (system) or one of [choices]. */
    fun current(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
        val language = if (locales == null || locales.isEmpty) "" else locales[0].language
        if (language in choices) language else ""
    } else legacy(context)

    /** Switches the language. Android 13+ recreates the screens itself; before that the activity is recreated here. */
    fun set(activity: Activity, choice: String) {
        val tag = if (choice in choices) choice else ""
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        if (tag == legacy(activity)) return
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).commit()
        stored = tag
        Locale.setDefault(locale(tag) ?: systemLocale())
        changed(activity.applicationContext)
        activity.recreate()
    }

    /**
     * At start: Android 12 and older get the default locale (dates in notifications) even when no screen
     * was opened (a push or boot start); Android 13+ takes over a choice stored before the phone was updated.
     */
    fun init(app: Context) {
        if (Build.VERSION.SDK_INT >= 33) {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = prefs.getString(KEY, "") ?: ""
            if (old.isNotEmpty()) {
                val manager = app.getSystemService(LocaleManager::class.java)
                if (manager != null && manager.applicationLocales.isEmpty && old in choices) manager.applicationLocales = LocaleList.forLanguageTags(old)
                prefs.edit().remove(KEY).apply()
            }
        } else {
            Locale.setDefault(locale(legacy(app)) ?: systemLocale())
        }
    }

    /** The language changed: notification channel names and the ongoing notification follow it. */
    fun changed(app: Context) {
        Notifier.createChannels(app)
        MonitorService.update(app)
    }

    /** Android 12 and older: [base] showing text in the chosen language; unchanged for the system language and on 13+. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val locale = locale(legacy(base)) ?: return base
        Locale.setDefault(locale) // dates and day names (java.time) follow the default locale
        // Only the locale is overridden: everything else (dark mode, orientation) still follows the system
        return base.createConfigurationContext(Configuration().apply { setLocales(LocaleList(locale)) })
    }

    /**
     * The application context for Android 12 and older. Its resources follow the choice even when it
     * changes while the app runs (notifications are built from this context), and are rebuilt after a
     * configuration change (dark mode, system language), so nothing else goes stale.
     */
    fun localizedApp(base: Context): Context = if (Build.VERSION.SDK_INT >= 33) base else LocalizedContext(base)

    private class LocalizedContext(base: Context) : ContextWrapper(base) {
        private var cache: Pair<String, Resources>? = null

        @Synchronized fun invalidate() { cache = null }

        // Synchronized: a worker thread building notifications must not put back resources made before an invalidate
        @Synchronized override fun getResources(): Resources {
            val tag = legacy(baseContext)
            val locale = locale(tag) ?: return super.getResources()
            cache?.let { (t, r) -> if (t == tag) return r }
            val config = Configuration().apply { setLocales(LocaleList(locale)) }
            return createConfigurationContext(config).resources.also { cache = tag to it }
        }
    }

    /** Called by the application after a configuration change. */
    fun configurationChanged(appBase: Context?) {
        (appBase as? LocalizedContext)?.invalidate()
        if (Build.VERSION.SDK_INT < 33 && appBase != null) Locale.setDefault(locale(legacy(appBase)) ?: systemLocale())
    }

    private fun locale(tag: String): Locale? = if (tag.isEmpty()) null else Locale.forLanguageTag(tag)

    private fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0]
}
