package com.gametimer.protocol.hub

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) { block() }

internal actual fun relayHttpClient(maxFrame: Long): HttpClient =
    HttpClient(CIO) { install(WebSockets) { maxFrameSize = maxFrame } }
