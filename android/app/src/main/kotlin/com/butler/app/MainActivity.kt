package com.butler.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import com.butler.app.ui.chat.ChatFragment
import com.butler.app.ui.home.HomeFragment
import com.butler.app.ui.settings.SettingsFragment
import com.butler.app.ui.tools.ToolsFragment
import com.google.android.material.bottomnavigation.NavigationBarView

/**
 * MainActivity — Google MD3 标准主界面
 *
 * AppCompatActivity + NavigationBarView (MD3 pill 指示器) + Fragment 容器。
 * 启用 edge-to-edge，状态栏/导航栏透明，子布局通过 fitsSystemWindows 自动收缩安全区。
 * Fragment 切换保持手写事务（轻量，避免引入 Navigation Component 依赖）。
 */
class MainActivity : AppCompatActivity(), HomeFragment.TabSwitcher {

    private val homeFragment by lazy { HomeFragment() }
    private val chatFragment by lazy { ChatFragment() }
    private val toolsFragment by lazy { ToolsFragment() }
    private val settingsFragment by lazy { SettingsFragment() }

    private var current: Fragment = homeFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        // SplashScreen API 必须在 super.onCreate 之前
        installSplashScreen()
        // edge-to-edge：让内容延伸到状态栏/导航栏后面，由 fitsSystemWindows 处理 inset
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<NavigationBarView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            val next = when (item.itemId) {
                R.id.nav_home -> homeFragment
                R.id.nav_chat -> chatFragment
                R.id.nav_tools -> toolsFragment
                R.id.nav_settings -> settingsFragment
                else -> return@setOnItemSelectedListener false
            }
            switchTo(next)
            true
        }

        if (savedInstanceState == null) {
            switchTo(homeFragment)
        }
    }

    private fun switchTo(fragment: Fragment) {
        if (fragment === current) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commitNowAllowingStateLoss()
        current = fragment
    }

    override fun switchTab(menuId: Int) {
        findViewById<NavigationBarView?>(R.id.bottom_nav)?.selectedItemId = menuId
    }
}

