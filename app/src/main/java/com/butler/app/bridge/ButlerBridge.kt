package com.butler.app.bridge

import com.butler.app.Logger
import com.butler.app.bridge.model.Message
import com.butler.app.bridge.model.Settings
import com.chaquo.python.Python
import com.chaquo.python.PyObject

/**
 * Bridge between Kotlin Android and Python Butler core
 * Uses Chaquopy for Python runtime integration
 */
class ButlerBridge {

    private var butlerModule: PyObject? = null
    private var isInitialized = false

    /**
     * Initialize Butler core
     */
    @Synchronized
    fun initialize() {
        if (isInitialized) return

        try {
            val python = Python.getInstance()
            butlerModule = python.getModule("butler")

            // Call Butler initialization
            butlerModule?.callAttr("initialize")

            isInitialized = true
            Logger.d(TAG, "Butler initialized successfully")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to initialize Butler: ${e.message}", e)
            throw e
        }
    }

    /**
     * Send a message to Butler and get response
     */
    fun sendMessage(message: String): String {
        ensureInitialized()

        return try {
            val result = butlerModule?.callAttr("process_message", message)
            result?.toString() ?: "No response from Butler"
        } catch (e: Exception) {
            Logger.e(TAG, "Error sending message: ${e.message}", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Get current settings
     */
    fun getSettings(): Settings {
        ensureInitialized()

        return try {
            val result = butlerModule?.callAttr("get_settings")
            val dict = result?.toJava(PyObject::class.java) as? PyObject
                ?: return Settings()

            Settings(
                apiKey = dict.get("api_key")?.toString() ?: "",
                voiceEnabled = dict.get("voice_enabled")?.toBoolean() ?: true,
                darkMode = dict.get("dark_mode")?.toBoolean() ?: false,
                language = dict.get("language")?.toString() ?: "en",
                speechRate = dict.get("speech_rate")?.toFloat() ?: 1.0f,
                wakeWord = dict.get("wake_word")?.toString() ?: "Butler"
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Error getting settings: ${e.message}")
            Settings()
        }
    }

    /**
     * Update settings
     */
    fun updateSettings(settings: Settings) {
        ensureInitialized()

        try {
            butlerModule?.callAttr(
                "update_settings",
                mapOf(
                    "api_key" to settings.apiKey,
                    "voice_enabled" to settings.voiceEnabled,
                    "dark_mode" to settings.darkMode,
                    "language" to settings.language,
                    "speech_rate" to settings.speechRate,
                    "wake_word" to settings.wakeWord
                )
            )
            Logger.d(TAG, "Settings updated")
        } catch (e: Exception) {
            Logger.e(TAG, "Error updating settings: ${e.message}")
        }
    }

    /**
     * Start voice listening
     */
    fun startListening(): Boolean {
        ensureInitialized()

        return try {
            val result = butlerModule?.callAttr("start_voice")
            result?.toBoolean() ?: false
        } catch (e: Exception) {
            Logger.e(TAG, "Error starting voice: ${e.message}")
            false
        }
    }

    /**
     * Stop voice listening and return transcribed text
     */
    fun stopListening(): String {
        ensureInitialized()

        return try {
            val result = butlerModule?.callAttr("stop_voice")
            result?.toString() ?: ""
        } catch (e: Exception) {
            Logger.e(TAG, "Error stopping voice: ${e.message}")
            ""
        }
    }

    /**
     * Text-to-speech
     */
    fun speak(text: String) {
        ensureInitialized()

        try {
            butlerModule?.callAttr("speak", text)
        } catch (e: Exception) {
            Logger.e(TAG, "Error speaking: ${e.message}")
        }
    }

    /**
     * Execute Python code via interpreter
     */
    fun executeCode(code: String): String {
        ensureInitialized()

        return try {
            val result = butlerModule?.callAttr("execute_code", code)
            result?.toString() ?: "Code executed with no output"
        } catch (e: Exception) {
            Logger.e(TAG, "Error executing code: ${e.message}")
            "Error: ${e.message}"
        }
    }

    /**
     * Call a specific plugin
     */
    fun callPlugin(pluginName: String, action: String, params: Map<String, Any>): String {
        ensureInitialized()

        return try {
            val result = butlerModule?.callAttr("call_plugin", pluginName, action, params)
            result?.toString() ?: "{}"
        } catch (e: Exception) {
            Logger.e(TAG, "Error calling plugin: ${e.message}")
            "{\"error\": \"${e.message}\"}"
        }
    }

    /**
     * Get list of available plugins
     */
    fun getPlugins(): List<String> {
        ensureInitialized()

        return try {
            val result = butlerModule?.callAttr("get_plugins")
            result?.toList()?.mapNotNull { it.toString() } ?: emptyList()
        } catch (e: Exception) {
            Logger.e(TAG, "Error getting plugins: ${e.message}")
            emptyList()
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        if (!isInitialized) return

        try {
            butlerModule?.callAttr("cleanup")
            isInitialized = false
            Logger.d(TAG, "Butler cleanup complete")
        } catch (e: Exception) {
            Logger.e(TAG, "Error during cleanup: ${e.message}")
        }
    }

    /**
     * Ensure Butler is initialized
     */
    private fun ensureInitialized() {
        if (!isInitialized) {
            initialize()
        }
    }

    companion object {
        private const val TAG = "ButlerBridge"
    }
}
