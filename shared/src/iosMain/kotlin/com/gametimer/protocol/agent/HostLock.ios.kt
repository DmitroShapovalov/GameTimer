package com.gametimer.protocol.agent

import platform.Foundation.NSRecursiveLock

internal actual fun newHostLock(): HostLock = object : HostLock {
    private val lock = NSRecursiveLock()
    override fun lock() = lock.lock()
    override fun unlock() = lock.unlock()
}
