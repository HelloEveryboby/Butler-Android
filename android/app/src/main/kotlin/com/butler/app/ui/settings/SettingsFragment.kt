package com.butler.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.butler.app.R
import com.butler.app.config.RuntimeConfig

/**
 * SettingsFragment — 设置页（MD3）
 *
 * 显示当前云端 API 状态，点击「云端 API」打开 RuntimeConfigActivity。
 */
class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View?>(R.id.row_deployment)?.setOnClickListener {
            startActivity(Intent(requireContext(), com.butler.app.RuntimeConfigActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        view?.findViewById<TextView?>(R.id.value_deployment)?.let { tv ->
            val cfg = RuntimeConfig.load(requireContext())
            tv.text = RuntimeConfig.statusLabel(cfg)
        }
    }
}
