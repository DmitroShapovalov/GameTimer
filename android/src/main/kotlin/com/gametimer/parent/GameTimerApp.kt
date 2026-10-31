package com.gametimer.parent

import android.app.Application
import com.gametimer.protocol.noise.Sodium
import kotlinx.coroutines.runBlocking

class GameTimerApp : Application() {
    lateinit var store: HubStore
        private set

    /** The links to the paired PCs, shared by the screens and [MonitorService]. */
    lateinit var hub: LinkHub
        private set

    override fun onCreate() {
        super.onCreate()
        runBlocking { Sodium.initialize() } // loads libsodium once (a few ms); everything below needs it
        store = HubStore(this)
        hub = LinkHub(this, store)
        Push.init(this)
        Push.refresh(this) { hub.pushTokenChanged() }
    }
}
