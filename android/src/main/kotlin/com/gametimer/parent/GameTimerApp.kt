package com.gametimer.parent

import android.app.Application
import com.gametimer.protocol.noise.Sodium
import kotlinx.coroutines.runBlocking

class GameTimerApp : Application() {
    lateinit var store: HubStore
        private set

    override fun onCreate() {
        super.onCreate()
        runBlocking { Sodium.initialize() } // loads libsodium once; fast
        store = HubStore(this)
    }
}
