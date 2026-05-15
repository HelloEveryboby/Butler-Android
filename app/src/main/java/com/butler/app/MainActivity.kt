package com.butler.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.butler.app.ui.ButlerAppUI
import com.butler.app.ui.theme.ButlerAndroidTheme

/**
 * Main Activity - Entry point for Butler Android
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ButlerAndroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ButlerAppUI(
                        onExit = { finish() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cleanup Python resources
        try {
            com.chaquo.python.Python.getInstance()
                .getModule("butler")
                .callAttr("cleanup")
        } catch (e: Exception) {
            Logger.e(TAG, "Cleanup error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
