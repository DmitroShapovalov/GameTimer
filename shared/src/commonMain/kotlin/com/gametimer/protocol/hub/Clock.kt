package com.gametimer.protocol.hub

/**
 * A monotonic clock that keeps running while the device sleeps (Android: elapsedRealtime, iOS:
 * CLOCK_MONOTONIC). The countdown is shown from it (§7.1), never from the PC's wall clock.
 */
internal expect fun elapsedRealtimeNanos(): Long
