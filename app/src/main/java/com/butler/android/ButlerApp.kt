package com.butler.android

import android.app.Application
import com.pybridge.core.Python

class ButlerApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Extract skills to internal storage for SkillManager
        AssetExtractor.extractSkills(this)

        // Initialize PyBridge Python runtime
        if (!Python.isInitialized()) {
            Python.initialize(this)
        }
    }
}