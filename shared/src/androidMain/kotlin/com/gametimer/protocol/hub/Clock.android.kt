package com.gametimer.protocol.hub

internal actual fun elapsedRealtimeNanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()

internal actual fun currentEpochMillis(): Long = System.currentTimeMillis()
