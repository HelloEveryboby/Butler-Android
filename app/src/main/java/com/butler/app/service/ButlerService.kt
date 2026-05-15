package com.butler.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.butler.app.Logger
import com.butler.app.MainActivity
import com.chaquo.python.Python

/**
 * Foreground service for Butler background tasks
 * Keeps the Python runtime alive when app is in background
 */
class ButlerService : Service() {

    private var isServiceRunning = false

    override fun onCreate() {
        super.onCreate()
        Logger.d(TAG, "ButlerService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.d(TAG, "ButlerService starting")

        startForeground(NOTIFICATION_ID, createNotification())

        // Start Python if not already running
        if (!Python.isStarted()) {
            Python.start(null)
        }

        isServiceRunning = true

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Logger.d(TAG, "ButlerService destroyed")
        isServiceRunning = false

        // Cleanup Python resources
        try {
            val python = Python.getInstance()
            python.getModule("butler").callAttr("cleanup")
        } catch (e: Exception) {
            Logger.e(TAG, "Cleanup error: ${e.message}")
        }
    }

    /**
     * Create notification channel for Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Butler Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Butler running in background"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create foreground notification
     */
    private fun createNotification(): Notification {
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Butler")
            .setContentText("Listening for commands...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "ButlerService"
        private const val CHANNEL_ID = "butler_service_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
