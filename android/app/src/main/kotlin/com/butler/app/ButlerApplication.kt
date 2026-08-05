package com.butler.app

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * ButlerApplication — MD3 动态取色入口
 *
 * 在 Android 12+ (API 31+) 上，让所有 Activity 自动应用基于壁纸的动态取色 (Material You)。
 * 在更低版本上回退到 themes.xml 中定义的静态 MD3 色板。
 */
class ButlerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 启用 Material You 动态取色：Android 12+ 生效，低版本自动忽略
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
