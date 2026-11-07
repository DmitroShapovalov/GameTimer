package com.gametimer.protocol.agent

import java.util.concurrent.locks.ReentrantLock

internal actual fun newHostLock(): HostLock = object : HostLock {
    private val lock = ReentrantLock()
    override fun lock() = lock.lock()
    override fun unlock() = lock.unlock()
}
