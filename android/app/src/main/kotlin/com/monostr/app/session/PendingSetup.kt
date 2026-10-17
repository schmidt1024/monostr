package com.monostr.app.session

import kotlinx.serialization.Serializable

/**
 * What a new account still has to publish after "Let's go" (spec 2026-10-17 §4/§7): the parts
 * to send and which of them are done. Holds no secret. An empty [follows] list means there is
 * no kind 3 to send, so [followsDone] starts true.
 */
@Serializable
data class PendingSetup(
    val name: String,
    val relays: List<String>,
    val dmRelays: List<String>,
    val follows: List<String>,
    val profileDone: Boolean = false,
    val relaysDone: Boolean = false,
    val dmRelaysDone: Boolean = false,
    val followsDone: Boolean = false,
)
