package com.butler.app

import android.app.Application
import com.chaquo.python.Python

/**
 * Butler Android Application class
 * Initializes Python runtime via Chaquopy
 */
class ButlerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Python runtime
        if (!Python.isStarted()) {
            Python.start(null)
        }

        // Get Python instance for later use
        val python = Python.getInstance()

        // Load Butler modules
        try {
            val butlerModule = python.getModule("butler")
            Logger.d(TAG, "Butler module loaded successfully")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load Butler module: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ButlerApp"
    }
}

/**
 * Simple logging utility
 */
object Logger {
    private const val TAG = "ButlerAndroid"

    fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        android.util.Log.w(tag, message)
    }

    fun e(tag: String, message: String) {
        android.util.Log.e(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable?) {
        android.util.Log.e(tag, message, throwable)
    }
}
