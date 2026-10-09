package com.monostr.tips.watcher

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /v1/info` response. */
@Serializable
data class WatcherInfo(
    val pubkey: String,
    val relays: List<String>,
    val network: String,
    val height: Long,
    val version: String,
)

@Serializable
internal data class RegisterRequest(
    val address: String,
    @SerialName("view_key") val viewKey: String,
)

@Serializable
internal data class RegisterResponse(
    @SerialName("watcher_pubkey") val watcherPubkey: String,
)

@Serializable
internal data class ErrorResponse(
    val error: String? = null,
)
