package com.butler.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.butler.app.bridge.ButlerBridge
import com.butler.app.bridge.model.Message
import com.butler.app.bridge.model.Settings
import com.butler.app.bridge.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI State for Butler app
 */
data class ButlerUIState(
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isProcessing: Boolean = false,
    val processingStatus: String = "",
    val isListening: Boolean = false,
    val isRecording: Boolean = false,
    val audioAmplitude: Float = 0f,
    val voiceEnabled: Boolean = false,
    val showSettings: Boolean = false,
    val settings: Settings = Settings(),
    val error: String? = null
)

/**
 * Main ViewModel for Butler app
 */
class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ButlerUIState())
    val uiState: StateFlow<ButlerUIState> = _uiState.asStateFlow()

    private val bridge = ButlerBridge()
    private var messageIdCounter = 0L

    init {
        initializeButler()
    }

    /**
     * Initialize Butler core via Chaquopy bridge
     */
    private fun initializeButler() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isProcessing = true, processingStatus = "Initializing Butler...") }

                withContext(Dispatchers.IO) {
                    bridge.initialize()
                }

                // Load default settings
                val settings = bridge.getSettings()
                _uiState.update { it.copy(settings = settings, voiceEnabled = settings.voiceEnabled) }

                _uiState.update { it.copy(isProcessing = false, processingStatus = "") }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        error = "Failed to initialize: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Update input text
     */
    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /**
     * Send a message to Butler
     */
    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) return

        val userMessage = Message(
            id = messageIdCounter++,
            role = MessageRole.USER,
            content = text,
            timestamp = System.currentTimeMillis()
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
                isProcessing = true,
                processingStatus = "Thinking..."
            )
        }

        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    bridge.sendMessage(text)
                }

                val butlerMessage = Message(
                    id = messageIdCounter++,
                    role = MessageRole.ASSISTANT,
                    content = response,
                    timestamp = System.currentTimeMillis()
                )

                _uiState.update {
                    it.copy(
                        messages = it.messages + butlerMessage,
                        isProcessing = false,
                        processingStatus = ""
                    )
                }

                // Speak response if voice is enabled
                if (_uiState.value.settings.voiceEnabled) {
                    bridge.speak(response)
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        error = "Error: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Toggle voice input
     */
    fun toggleVoiceInput() {
        val newListeningState = !_uiState.value.isListening
        _uiState.update { it.copy(isListening = newListeningState) }

        if (newListeningState) {
            startVoiceListening()
        } else {
            stopVoiceListening()
        }
    }

    /**
     * Start voice listening
     */
    private fun startVoiceListening() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    bridge.startListening()
                }
                _uiState.update { it.copy(isRecording = true) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isListening = false,
                        error = "Voice error: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Stop voice listening
     */
    private fun stopVoiceListening() {
        viewModelScope.launch {
            try {
                val voiceText = withContext(Dispatchers.IO) {
                    bridge.stopListening()
                }

                _uiState.update { it.copy(isRecording = false, isListening = false) }

                if (voiceText.isNotBlank()) {
                    _uiState.update { it.copy(inputText = voiceText) }
                    sendMessage()
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        isListening = false,
                        error = "Voice error: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Pause voice input when app goes to background
     */
    fun pauseVoiceInput() {
        if (_uiState.value.isListening) {
            stopVoiceListening()
        }
    }

    /**
     * Resume voice input when app comes to foreground
     */
    fun resumeVoiceInput() {
        // Optionally auto-resume if it was active
    }

    /**
     * Toggle settings sheet
     */
    fun toggleSettings() {
        _uiState.update { it.copy(showSettings = !it.showSettings) }
    }

    /**
     * Update settings
     */
    fun updateSettings(settings: Settings) {
        _uiState.update { it.copy(settings = settings, voiceEnabled = settings.voiceEnabled) }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                bridge.updateSettings(settings)
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            bridge.cleanup()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
}
