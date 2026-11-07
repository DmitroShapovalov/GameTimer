package com.gametimer.protocol.hub

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin) { block() }

internal actual fun relayHttpClient(maxFrame: Long): HttpClient =
    HttpClient(Darwin) { install(WebSockets) { maxFrameSize = maxFrame } }
