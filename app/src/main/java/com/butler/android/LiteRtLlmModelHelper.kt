package com.butler.android

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File

/**
 * LiteRT (formerly TensorFlow Lite) LLM Inference implementation.
 * Integrates com.google.ai.edge.litertlm (under mock or loaded conditions).
 */
class LiteRtLlmModelHelper : LlmModelHelper {

    private var isInitialized = false
    private var modelFile: File? = null
    private var shouldStop = false

    override fun initialize(context: Context, model: Model, onDone: (Boolean) -> Unit) {
        val normalizedName = model.name.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val targetDir = File(context.getExternalFilesDir(null), "$normalizedName/${model.version}")
        val file = File(targetDir, model.downloadFileName)

        if (!file.exists()) {
            Log.e("LiteRtLlmModelHelper", "Model file not found at ${file.absolutePath}")
            onDone(false)
            return
        }

        modelFile = file
        // Here, we would construct the com.google.ai.edge.litertlm.Session config.
        // We simulate the Session initialization and warmup process.
        Log.i("LiteRtLlmModelHelper", "Initializing LiteRT LLM Session using ${file.name}")

        isInitialized = true
        onDone(true)
    }

    override fun runInference(input: String, images: List<Bitmap>, resultListener: ResultListener) {
        if (!isInitialized) {
            resultListener.onError("LiteRT Session not initialized")
            return
        }

        shouldStop = false
        // Simulate streaming token generator run on a separate background thread
        Thread {
            try {
                val words = "This is a streaming simulated response from LiteRT LLM engine executing on device. All data is processed locally to protect your privacy and reduce latency.".split(" ")
                for ((index, word) in words.withIndex()) {
                    if (shouldStop) {
                        Log.i("LiteRtLlmModelHelper", "Inference stopped by request.")
                        break
                    }
                    Thread.sleep(120) // Simulated streaming delay
                    val isLast = (index == words.size - 1)
                    resultListener.onResult("$word ", isLast)
                }
            } catch (e: Exception) {
                resultListener.onError(e.message ?: "Unknown inference error")
            }
        }.start()
    }

    override fun stopResponse() {
        shouldStop = true
    }

    override fun cleanUp(onDone: () -> Unit) {
        Log.i("LiteRtLlmModelHelper", "Cleaning up LiteRT Session.")
        isInitialized = false
        modelFile = null
        onDone()
    }
}
