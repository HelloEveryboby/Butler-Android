package com.butler.app.bridge.model

/**
 * Chat message model
 */
data class Message(
    val id: Long,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val isCode: Boolean = false,
    val isError: Boolean = false
)

/**
 * Message role
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

/**
 * Settings model
 */
data class Settings(
    val apiKey: String = "",
    val voiceEnabled: Boolean = true,
    val darkMode: Boolean = false,
    val language: String = "en",
    val speechRate: Float = 1.0f,
    val wakeWord: String = "Butler"
)

/**
 * Voice state
 */
data class VoiceState(
    val isListening: Boolean = false,
    val isRecording: Boolean = false,
    val amplitude: Float = 0f,
    val transcription: String = ""
)

/**
 * Plugin info
 */
data class PluginInfo(
    val name: String,
    val description: String,
    val actions: List<String>,
    val enabled: Boolean
)

/**
 * Command execution result
 */
data class CommandResult(
    val success: Boolean,
    val output: String,
    val error: String? = null
)
