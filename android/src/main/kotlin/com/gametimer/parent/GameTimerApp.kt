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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.configurationChanged(baseContext)
    }

    override fun onCreate() {
        super.onCreate()
        runBlocking { Sodium.initialize() } // loads libsodium once (a few ms); everything below needs it
        store = HubStore(this)
        hub = LinkHub(this, store)
        Push.init(this)
        Push.refresh(this) { hub.pushTokenChanged() }
    }
}
