package com.cloudagentos.app.data

import java.time.Instant

enum class MessageRole { USER, AGENT }

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val agentTag: String? = null,
    val timestamp: Instant = Instant.now(),
    val isStreaming: Boolean = false
)

data class ActionConfirm(
    val actionId: String,
    val description: String,
    val agentTag: String? = null
)

data class TextMessage(
    val type: String = "text",
    val content: String,
    val user_id: String
)

data class AudioMessage(
    val type: String = "audio",
    val content: String,
    val user_id: String
)

data class ActionResponseMessage(
    val type: String = "action_response",
    val action_id: String,
    val approved: Boolean,
    val user_id: String
)

data class ServerMessage(
    val type: String,
    val content: String? = null,
    val agent: String? = null,
    val action_id: String? = null,
    val action_description: String? = null,
    val message_id: String? = null,
    val done: Boolean? = null,
    val error: String? = null,
    val data: Map<String, Any?>? = null
)
