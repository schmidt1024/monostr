package com.monostr.app.ui.common

import com.monostr.nostr.model.Note

/**
 * The id a conversation is muted by (backlog 81): its thread root, lower-case. Mirrors `ThreadRepository.observe`:
 * a comment directly under an article is the root of its own thread.
 */
fun Note.threadKey(): String = (if (articleParent != null) id else rootId ?: id).lowercase()
