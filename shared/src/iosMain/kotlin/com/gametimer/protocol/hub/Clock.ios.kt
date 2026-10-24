package com.gametimer.protocol.hub

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime_nsec_np

@OptIn(ExperimentalForeignApi::class)
internal actual fun elapsedRealtimeNanos(): Long = clock_gettime_nsec_np(CLOCK_MONOTONIC.toUInt()).toLong()
