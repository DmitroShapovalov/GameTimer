package com.gametimer.child

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The block page over a chosen app (normal mode). Back and the button go to the home screen. It records
 * the block delay itself, when it is actually on screen.
 */
class BlockActivity : ComponentActivity() {
    private var label by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        label = labelOf(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Time's up", style = MaterialTheme.typography.headlineMedium)
                        Text("$label is blocked for now.", Modifier.padding(top = 12.dp, bottom = 24.dp))
                        Button(onClick = ::goHome) { Text("OK") }
                    }
                }
            }
        }
    }

    // A single instance: a later block reuses this page, so it takes the new app's name
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        label = labelOf(intent)
    }

    override fun onResume() {
        super.onResume()
        val prefs = SpikePrefs(this)
        val pending = prefs.pendingBlock() ?: return
        prefs.clearPendingBlock()
        val ms = System.currentTimeMillis() - pending.fromMs
        // Not counted: a game open before the service (re)started, or a clock change in between
        val counted = pending.measured && ms in 0..600_000
        if (counted) prefs.addLatency(pending.source, ms)
        SpikeLog.add(this, "block page shown over ${pending.pkg} after $ms ms [${pending.source}]${if (counted) "" else " (not counted)"}")
    }

    private fun labelOf(intent: Intent): String {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object { const val EXTRA_PACKAGE = "pkg" }
}
