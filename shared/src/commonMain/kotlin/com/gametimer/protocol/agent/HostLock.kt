package com.gametimer.protocol.agent

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** A reentrant lock usable from any thread (the host's state is shared by sessions and the app). */
internal interface HostLock {
    fun lock()
    fun unlock()
}

internal expect fun newHostLock(): HostLock

@OptIn(ExperimentalContracts::class)
internal inline fun <T> HostLock.withLock(block: () -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
