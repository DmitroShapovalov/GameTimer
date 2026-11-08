package com.gametimer.child

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gametimer.play.AskResult
import com.gametimer.play.BlockReason
import kotlinx.coroutines.delay

/**
 * The block page over a limited game: why ("time's up" / "not now"), and "Ask for more time" (a parent answers
 * on their phone). Back and OK go to the home screen; it closes by itself once the game is allowed again (a
 * parent said yes or added time).
 */
class BlockActivity : ComponentActivity() {
    private var pkg by mutableStateOf("")
    private var reason by mutableStateOf(BlockReason.TimeUp)
    private var askResult by mutableStateOf<AskResult?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        read(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF66BB6A)) else lightColorScheme(primary = Color(0xFF2E7D32))
            val play by AgentRuntime.play.collectAsState()
            // Allowed again (a parent's yes, more time, the schedule lifted): back to the game
            LaunchedEffect(play) { AgentRuntime.blockReason(pkg)?.let { reason = it } ?: finish() }
            // Nothing changes by itself when a request goes unanswered: after 15 minutes the child may ask again
            LaunchedEffect(Unit) { while (true) { delay(30_000); AgentRuntime.refreshPlay() } }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(if (reason == BlockReason.TimeUp) R.string.block_time_up else R.string.block_not_now),
                            style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Text(stringResource(if (reason == BlockReason.TimeUp) R.string.block_time_up_text else R.string.block_not_now_text, label()),
                            textAlign = TextAlign.Center)
                        if (reason == BlockReason.TimeUp && play.parents > 0) {
                            val asked = play.asked
                            OutlinedButton(onClick = { askResult = AgentRuntime.askForMoreTime() }, enabled = !asked,
                                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.block_ask)) }
                            val note = when {
                                asked -> R.string.block_asked
                                askResult == AskResult.TooSoon -> R.string.block_too_soon
                                else -> null
                            }
                            note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
                        }
                        Button(onClick = ::goHome, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ok)) }
                    }
                }
            }
        }
    }

    // A single instance: a later block reuses this page with the new app and reason
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)
    }

    override fun onResume() {
        super.onResume()
        showing = true
        shownAt = android.os.SystemClock.elapsedRealtime()
    }

    override fun onPause() {
        showing = false
        super.onPause()
    }

    private fun read(intent: Intent) {
        pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        reason = runCatching { BlockReason.valueOf(intent.getStringExtra(EXTRA_REASON) ?: "") }.getOrDefault(BlockReason.TimeUp)
        askResult = null
    }

    private fun label(): String =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_REASON = "reason"

        /** The page is on screen (the watcher doesn't open it twice). */
        @Volatile var showing = false

        /** When the page came up (elapsedRealtime). */
        @Volatile var shownAt = 0L
    }
}
