package com.gametimer.protocol.hub

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/** An HttpClient on the platform's usual engine: OkHttp on Android / JVM, Darwin on iOS. */
internal expect fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * The agent's relay socket (§10.2): an engine that enforces [maxFrame] on inbound frames before buffering them,
 * so a misbehaving relay can't exhaust memory (CIO on Android / JVM; Darwin on iOS caps messages at 1 MiB).
 */
internal expect fun relayHttpClient(maxFrame: Long): HttpClient
