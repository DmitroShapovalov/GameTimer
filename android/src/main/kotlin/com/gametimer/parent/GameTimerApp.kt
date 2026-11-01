package com.gametimer.parent

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.gametimer.protocol.noise.Sodium
import kotlinx.coroutines.runBlocking

class GameTimerApp : Application() {
    lateinit var store: HubStore
        private set

    /** The links to the paired PCs, shared by the screens and [MonitorService]. */
    lateinit var hub: LinkHub
        private set

    // Android 12 and older: text in the language chosen in the app (AppLocale)
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.localizedApp(base))

    private var locales: String? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.configurationChanged(baseContext)
        // A new language (the app's own on Android 13+, or the system's): channel names and the ongoing notification follow
        val now = newConfig.locales.toLanguageTags()
        if (locales != null && now != locales) AppLocale.changed(this)
        locales = now
    }

    override fun onCreate() {
        super.onCreate()
        AppLocale.init(this)
        // The language the configuration callbacks will report: the app's own on 13+, the system's before
        locales = (if (android.os.Build.VERSION.SDK_INT >= 33) resources else android.content.res.Resources.getSystem()).configuration.locales.toLanguageTags()
        runBlocking { Sodium.initialize() } // loads libsodium once (a few ms); everything below needs it
        store = HubStore(this)
        hub = LinkHub(this, store)
        Push.init(this)
        Push.refresh(this) { hub.pushTokenChanged() }
    }
}
