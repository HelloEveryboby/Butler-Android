package com.butler.app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.butler.app.R

/**
 * HomeFragment — 首页（MD3 风格）
 */
class HomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View?>(R.id.quick_chat)?.setOnClickListener {
            (activity as? TabSwitcher)?.switchTab(R.id.nav_chat)
        }
        view.findViewById<View?>(R.id.quick_tools)?.setOnClickListener {
            (activity as? TabSwitcher)?.switchTab(R.id.nav_tools)
        }
        view.findViewById<View?>(R.id.quick_settings)?.setOnClickListener {
            (activity as? TabSwitcher)?.switchTab(R.id.nav_settings)
        }
    }

    interface TabSwitcher {
        fun switchTab(menuId: Int)
    }
}
