package com.butler.app.island

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * DynamicIslandOverlay - Android 灵动岛悬浮窗
 *
 * 在 Android 刘海/挖孔区域模拟类似 iOS 灵动岛的效果。
 * 支持两种状态：
 *   - COMPACT: 小圆点（类似灵动岛收缩态）
 *   - EXPANDED: 展开显示标题+内容（类似灵动岛展开态）
 *
 * 通过 Capacitor Plugin 桥接到前端 TS 控制。
 */
class DynamicIslandOverlay(private val context: Context) {

    enum class State {
        HIDDEN,     // 完全隐藏
        COMPACT,    // 小圆点
        EXPANDED,   // 展开内容
        EXPANDING,  // 正在展开动画中
        COLLAPSING  // 正在收缩动画中
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var compactView: View? = null
    private var expandedView: View? = null
    private var currentState = State.HIDDEN

    private var currentTitle: String = ""
    private var currentContent: String = ""
    private var currentIcon: String = "info"

    // 灵动岛尺寸（dp）
    private val compactWidthDp = 36f
    private val compactHeightDp = 36f
    private val expandedWidthDp = 340f
    private val expandedHeightDp = 100f

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics
        ).roundToInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showCompact(icon: String = "info", title: String = "", content: String = "") {
        if (currentState == State.EXPANDED) {
            // 先收缩再显示
            hideExpanded {
                showCompact(icon, title, content)
            }
            return
        }

        currentTitle = title
        currentContent = content
        currentIcon = icon

        if (compactView != null) {
            // 更新已有 view
            updateCompactContent()
            return
        }

        val wmParams = createWindowParams()
        val islandWidth = dpToPx(compactWidthDp)
        val islandHeight = dpToPx(compactHeightDp)

        // 创建圆角黑色背景
        val bg = GradientDrawable().apply {
            setColor(0xFF1C1C1E.toInt())
            cornerRadii = floatArrayOf(
                islandHeight / 2f, islandHeight / 2f,
                islandHeight / 2f, islandHeight / 2f,
                islandHeight / 2f, islandHeight / 2f,
                islandHeight / 2f, islandHeight / 2f
            )
        }

        val view = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(islandWidth, islandHeight)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = bg
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
        }

        // 状态指示圆点
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(8f), dpToPx(8f))
            background = GradientDrawable().apply {
                setColor(getIconColor(icon))
                cornerRadii = floatArrayOf(4f, 4f, 4f, 4f, 4f, 4f, 4f, 4f)
            }
        }
        view.addView(dot)

        // 入场动画
        view.scaleX = 0f
        view.scaleY = 0f
        view.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(2f))
            .start()

        // 点击展开
        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private var startY = 0f

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        if (Math.abs(dx) < 10 && Math.abs(dy) < 10) {
                            expand()
                        }
                        return true
                    }
                }
                return false
            }
        })

        compactView = view
        try {
            windowManager.addView(view, wmParams)
            currentState = State.COMPACT
        } catch (e: Exception) {
            // 悬浮窗权限未授予
            compactView = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun expand() {
        if (currentState != State.COMPACT) return
        currentState = State.EXPANDING

        // 隐藏 compact
        hideCompact {
            // 创建展开视图
            val wmParams = createWindowParams()
            val expWidth = dpToPx(expandedWidthDp)
            val expHeight = dpToPx(expandedHeightDp)

            val bg = GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt())
                cornerRadii = floatArrayOf(
                    expHeight / 2f, expHeight / 2f,
                    expHeight / 2f, expHeight / 2f,
                    expHeight / 2f, expHeight / 2f,
                    expHeight / 2f, expHeight / 2f
                )
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(expWidth, expHeight)
                background = bg
                setPadding(dpToPx(16f), dpToPx(12f), dpToPx(16f), dpToPx(12f))
                gravity = Gravity.CENTER_VERTICAL
            }

            // 左侧图标区
            val iconBg = GradientDrawable().apply {
                setColor(0x33FFFFFF.toInt())
                cornerRadii = floatArrayOf(12f, 12f, 12f, 12f, 12f, 12f, 12f, 12f)
            }
            val iconContainer = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(48f), dpToPx(48f))
                background = iconBg
            }
            val iconView = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                setImageResource(getIconResource(currentIcon))
                setColorFilter(getIconColor(currentIcon))
            }
            iconContainer.addView(iconView)

            // 右侧文本区
            val textContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setMargins(dpToPx(12f), 0, 0, 0)
            }
            val titleView = TextView(context).apply {
                text = currentTitle.ifEmpty { "Butler"
                }
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val contentView = TextView(context).apply {
                text = currentContent.ifEmpty { "正在处理..."
                }
                setTextColor(0xAAFFFFFF.toInt())
                textSize = 11f
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            textContainer.addView(titleView)
            textContainer.addView(contentView)

            // 关闭按钮
            val closeBtn = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(24f), dpToPx(24f))
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(0xAAFFFFFF.toInt())
                setOnClickListener {
                    collapse()
                }
            }

            container.addView(iconContainer)
            container.addView(textContainer)
            container.addView(closeBtn)

            // 展开/收缩动画
            container.pivotX = expWidth / 2f
            container.pivotY = expHeight / 2f
            container.scaleX = 0.3f
            container.scaleY = 0.3f
            container.alpha = 0f

            try {
                windowManager.addView(container, wmParams)
                container.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(350)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        currentState = State.EXPANDED
                    }
                    .start()
                expandedView = container
            } catch (e: Exception) {
                currentState = State.COMPACT
            }
        }
    }

    fun collapse() {
        if (currentState != State.EXPANDED && currentState != State.EXPANDING) return
        currentState = State.COLLAPSING

        hideExpanded {
            showCompact(currentIcon, currentTitle, currentContent)
        }
    }

    fun update(title: String, content: String, icon: String? = null) {
        currentTitle = title
        currentContent = content
        icon?.let { currentIcon = it }

        if (currentState == State.COMPACT) {
            updateCompactContent()
        } else if (currentState == State.EXPANDED) {
            updateExpandedContent()
        }
    }

    fun hide(callback: (() -> Unit)? = null) {
        hideExpanded {
            hideCompact(callback)
        }
    }

    fun getState(): String = currentState.name

    // ── Internal Methods ────────────────────────────────────────

    private fun hideCompact(callback: (() -> Unit)? = null) {
        val view = compactView ?: run { callback?.invoke(); return }
        view.animate()
            .scaleX(0f).scaleY(0f).alpha(0f)
            .setDuration(250)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                try { windowManager.removeView(view) } catch (_: Exception) {}
                compactView = null
                currentState = State.HIDDEN
                callback?.invoke()
            }
            .start()
    }

    private fun hideExpanded(callback: (() -> Unit)? = null) {
        val view = expandedView ?: run { callback?.invoke(); return }
        view.animate()
            .scaleX(0.3f).scaleY(0.3f).alpha(0f)
            .setDuration(250)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                try { windowManager.removeView(view) } catch (_: Exception) {}
                expandedView = null
                callback?.invoke()
            }
            .start()
    }

    private fun updateCompactContent() {
        // Compact 模式下只更新圆点颜色
        compactView?.let { view ->
            if (view is LinearLayout && view.childCount > 0) {
                val dot = view.getChildAt(0)
                dot?.background = GradientDrawable().apply {
                    setColor(getIconColor(currentIcon))
                    cornerRadii = floatArrayOf(4f, 4f, 4f, 4f, 4f, 4f, 4f, 4f)
                }
            }
        }
    }

    private fun updateExpandedContent() {
        expandedView?.let { view ->
            if (view is LinearLayout) {
                // 结构: iconContainer(0) + textContainer(1) + closeBtn(2)
                if (view.childCount >= 2) {
                    val textContainer = view.getChildAt(1) as? LinearLayout
                    textContainer?.let { tc ->
                        if (tc.childCount >= 2) {
                            (tc.getChildAt(0) as? TextView)?.text = currentTitle.ifEmpty { "Butler" }
                            (tc.getChildAt(1) as? TextView)?.text = currentContent.ifEmpty { "正在处理..." }
                        }
                    }
                    val iconContainer = view.getChildAt(0) as? FrameLayout
                    iconContainer?.let { ic ->
                        if (ic.childCount > 0) {
                            (ic.getChildAt(0) as? ImageView)?.setColorFilter(getIconColor(currentIcon))
                        }
                    }
                }
            }
        }
    }

    private fun createWindowParams(): WindowManager.LayoutParams {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        val screenWidth = metrics.widthPixels

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 定位在刘海/状态栏区域
            y = dpToPx(8f)
            x = (screenWidth - dpToPx(compactWidthDp)) / 2
        }
    }

    private fun getIconColor(icon: String): Int {
        return when (icon.lowercase()) {
            "info", "default" -> 0xFF007AFF.toInt()   // 蓝色
            "success", "done" -> 0xFF34C759.toInt()    // 绿色
            "warning" -> 0xFFFF9500.toInt()           // 橙色
            "error" -> 0xFFFF3B30.toInt()             // 红色
            "processing", "loading" -> 0xFFAF52DE.toInt() // 紫色
            "music" -> 0xFFFF2D55.toInt()             // 粉红
            "voice", "mic" -> 0xFF5AC8FA.toInt()     // 浅蓝
            else -> 0xFF007AFF.toInt()
        }
    }

    private fun getIconResource(icon: String): Int {
        return when (icon.lowercase()) {
            "music" -> android.R.drawable.ic_media_play
            "voice", "mic" -> android.R.drawable.ic_btn_speak
            "success", "done" -> android.R.drawable.ic_menu_info_details
            else -> android.R.drawable.ic_dialog_info
        }
    }
}
