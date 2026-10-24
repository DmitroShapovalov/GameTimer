package com.gametimer.protocol.hub

internal actual fun elapsedRealtimeNanos(): Long = System.nanoTime()

internal actual fun currentEpochMillis(): Long = System.currentTimeMillis()
