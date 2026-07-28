package com.butler.app.wake

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * ButlerAccessibilityService - 无障碍服务
 *
 * 实现两种唤醒手势：
 * 1. 屏幕边缘滑动唤醒：从屏幕左/右边缘向内滑动，触发 Butler 唤醒
 * 2. 电源键双击唤醒：快速双按电源键，触发 Butler 唤醒
 *
 * 通过 EventBus 事件通知前端和 DynamicIsland。
 */
class ButlerAccessibilityService : AccessibilityService() {

    companion object {
        // 电源键双击的时间阈值 (ms)
        private const val POWER_DOUBLE_CLICK_THRESHOLD = 400L

        // 屏幕边缘滑动触发区域宽度 (dp)
        private const val EDGE_ZONE_DP = 20f

        // 屏幕边缘最小滑动距离 (dp)
        private const val EDGE_SWIPE_MIN_DP = 40f

        var isRunning = false
            private set

        // 回调接口（由 Plugin 设置）
        var wakeCallback: ((String) -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastPowerPressTime = 0L
    private var edgeOverlay: View? = null
    private var edgeWindowParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null

    // 边缘滑动状态
    private var edgeTouchStartX = 0f
    private var edgeTouchStartY = 0f
    private var isEdgeTouch = false

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics
        ).toInt()
    }

    // ── AccessibilityService 生命周期 ──────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true

        // 配置服务信息：监听按键事件和触摸事件
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }

        // 设置边缘滑动检测覆盖层
        setupEdgeDetection()

        wakeCallback?.invoke("accessibility_connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理无障碍事件，主要用 onKeyEvent
    }

    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_POWER) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val now = System.currentTimeMillis()
                if (now - lastPowerPressTime < POWER_DOUBLE_CLICK_THRESHOLD) {
                    // 双击电源键！触发唤醒
                    lastPowerPressTime = 0L // 防止三击触发
                    wakeCallback?.invoke("power_double_click")
                    showEdgeFlash()
                    return false // 不消费事件，让系统正常处理
                }
                lastPowerPressTime = now
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onInterrupt() {
        isRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        removeEdgeOverlay()
    }

    // ── 边缘滑动检测 ────────────────────────────────────────────

    private fun setupEdgeDetection() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager!!.defaultDisplay.getMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val edgeZone = dpToPx(EDGE_ZONE_DP)
        val swipeMin = dpToPx(EDGE_SWIPE_MIN_DP)

        // 创建透明覆盖层，仅在边缘区域接收触摸
        val overlay = View(this).apply {
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val x = event.rawX.toInt()
                        // 检测是否在左边缘或右边缘
                        if (x < edgeZone || x > screenWidth - edgeZone) {
                            edgeTouchStartX = event.rawX
                            edgeTouchStartY = event.rawY
                            isEdgeTouch = true
                            true
                        } else {
                            isEdgeTouch = false
                            false
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isEdgeTouch) true else false
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isEdgeTouch) {
                            val dx = event.rawX - edgeTouchStartX
                            val dy = event.rawY - edgeTouchStartY
                            // 从边缘向内滑动，dx 方向正确且距离足够
                            val inwardX = if (edgeTouchStartX < edgeZone) {
                                dx > 0 && dx > swipeMin // 从左向右
                            } else {
                                dx < 0 && -dx > swipeMin // 从右向左
                            }
                            if (inwardX) {
                                wakeCallback?.invoke("edge_swipe")
                                showEdgeFlash()
                            }
                            isEdgeTouch = false
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
        }

        edgeOverlay = overlay
        edgeWindowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager!!.addView(overlay, edgeWindowParams)
        } catch (e: Exception) {
            // 静默失败
        }
    }

    private fun removeEdgeOverlay() {
        try {
            edgeOverlay?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {}
        edgeOverlay = null
    }

    // ── 视觉反馈：边缘闪烁 ──────────────────────────────────────

    private fun showEdgeFlash() {
        val flash = View(this).apply {
            setBackgroundColor(0x33FFFFFF.toInt())
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dpToPx(3f),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(44) // 状态栏下方
        }

        try {
            windowManager?.addView(flash, params)
            flash.animate()
                .alpha(0f)
                .setDuration(600)
                .withEndAction {
                    try { windowManager?.removeView(flash) } catch (_: Exception) {}
                }
                .start()
        } catch (_: Exception) {}
    }
}
