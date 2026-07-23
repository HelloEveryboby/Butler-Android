package com.butler.app.wake

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
    name = "WakeManager",
    permissions = [
        Permission(
            alias = "accessibility",
            strings = ["android.permission.BIND_ACCESSIBILITY_SERVICE"]
        ),
        Permission(
            alias = "bluetooth",
            strings = [
                "android.permission.BLUETOOTH_CONNECT"
            ]
        )
    ]
)
class WakeManagerPlugin : Plugin() {

    private var lastWakeSource: String = ""
    private var lastWakeTime: Long = 0

    @PluginMethod
    fun isAccessibilityEnabled(call: PluginCall) {
        val enabled = isAccessibilityServiceRunning()
        call.resolve(JSObject().put("enabled", enabled))
    }

    @PluginMethod
    fun openAccessibilitySettings(call: PluginCall) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        call.resolve()
    }

    @PluginMethod
    fun isMediaButtonEnabled(call: PluginCall) {
        // 媒体按钮接收器在 Manifest 中静态注册，始终可用
        // 但需要确认音频焦点状态
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        call.resolve(JSObject().put("enabled", true))
    }

    @PluginMethod
    fun getLastWake(call: PluginCall) {
        val result = JSObject().apply {
            put("source", lastWakeSource)
            put("timestamp", lastWakeTime)
        }
        call.resolve(result)
    }

    @PluginMethod
    fun requestMediaButtonFocus(call: PluginCall) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // 注册媒体按钮接收器
        val receiver = ComponentName(context.packageName, MediaButtonReceiver::class.java.name)
        am.registerMediaButtonEventReceiver(receiver)

        // 设置回调
        MediaButtonReceiver.wakeCallback = { source, _ ->
            handleWake(source)
        }

        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun startListening(call: PluginCall) {
        // 1. 设置无障碍服务回调
        ButlerAccessibilityService.wakeCallback = { source ->
            handleWake(source)
        }

        // 2. 注册媒体按钮
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val receiver = ComponentName(context.packageName, MediaButtonReceiver::class.java.name)
            am.registerMediaButtonEventReceiver(receiver)
            MediaButtonReceiver.wakeCallback = { source, _ ->
                handleWake(source)
            }
        } catch (e: Exception) {
            // 静默处理
        }

        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun stopListening(call: PluginCall) {
        // 清除回调
        ButlerAccessibilityService.wakeCallback = null
        MediaButtonReceiver.wakeCallback = null

        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val receiver = ComponentName(context.packageName, MediaButtonReceiver::class.java.name)
            am.unregisterMediaButtonEventReceiver(receiver)
        } catch (e: Exception) {
            // 静默处理
        }

        call.resolve(JSObject().put("ok", true))
    }

    private fun handleWake(source: String) {
        lastWakeSource = source
        lastWakeTime = System.currentTimeMillis()

        // 通知前端 JS
        notifyListeners("wake", JSObject().apply {
            put("source", source)
            put("timestamp", lastWakeTime)
        })
    }

    private fun isAccessibilityServiceRunning(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES_CODES) {
            return false
        }
        val expected = ComponentName(context.packageName, ButlerAccessibilityService::class.java.name).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expected)
    }
}
