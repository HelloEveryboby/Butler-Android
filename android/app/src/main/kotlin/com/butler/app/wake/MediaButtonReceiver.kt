package com.butler.app.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent

/**
 * MediaButtonReceiver - 蓝牙耳机/有线耳机按键接收器
 *
 * 监听媒体按钮事件：
 * - 单击播放/暂停键 → 唤醒 Butler
 * - 双击下一首键 → 唤醒 Butler
 * - 长按 → 语音输入模式
 *
 * 耳机操作映射（常见蓝牙耳机）：
 *   单击 → KEYCODE_MEDIA_PLAY_PAUSE（播放/暂停）
 *   双击 → KEYCODE_MEDIA_NEXT（下一首）
 *   三击 → KEYCODE_MEDIA_PREVIOUS（上一首）
 */
class MediaButtonReceiver : BroadcastReceiver() {

    companion object {
        // 回调接口（由 Plugin 设置）
        var wakeCallback: ((String, Bundle?) -> Unit)? = null

        // 防抖：避免连续触发
        private const val DEBOUNCE_MS = 2000L
        private var lastTriggerTime = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == android.media.AudioManager.ACTION_MEDIA_BUTTON) {
            val event = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return

            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        // 单击播放/暂停键 → 唤醒 Butler
                        triggerWake(context, "media_play_pause")
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        // 双击（下一首）→ 唤醒 Butler
                        triggerWake(context, "media_next")
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        // 三击（上一首）→ 可选触发
                        triggerWake(context, "media_previous")
                    }
                    KeyEvent.KEYCODE_HEADSETHOOK -> {
                        // 通用耳机按键
                        triggerWake(context, "headset_hook")
                    }
                }
            }
        }
    }

    private fun triggerWake(context: Context, source: String) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < DEBOUNCE_MS) return
        lastTriggerTime = now

        // 通知回调
        wakeCallback?.invoke(source, null)

        // 也通过灵动岛显示
        try {
            val overlay = DynamicIslandOverlayHolder.getOverlay(context)
            when (source) {
                "media_play_pause", "headset_hook" -> {
                    overlay.showCompact("info", "Butler", "已唤醒")
                    overlay.expand()
                }
                "media_next" -> {
                    overlay.showCompact("success", "Butler", "已唤醒")
                }
                "media_previous" -> {
                    overlay.showCompact("info", "Butler", "已唤醒")
                }
            }
        } catch (_: Exception) {}
    }
}

/**
 * DynamicIslandOverlayHolder - 持有全局 DynamicIslandOverlay 实例
 * 让 MediaButtonReceiver 能访问灵动岛（不持有 context 的静态对象）
 */
object DynamicIslandOverlayHolder {
    private var overlayInstance: com.butler.app.island.DynamicIslandOverlay? = null

    fun setOverlay(overlay: com.butler.app.island.DynamicIslandOverlay) {
        overlayInstance = overlay
    }

    fun getOverlay(context: Context): com.butler.app.island.DynamicIslandOverlay {
        if (overlayInstance == null) {
            overlayInstance = com.butler.app.island.DynamicIslandOverlay(context)
        }
        return overlayInstance!!
    }
}
