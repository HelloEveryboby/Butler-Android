package com.butler.app.ui.tools

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.butler.app.R

/**
 * ToolsFragment — 工具中心（MD3 卡片网格）
 */
class ToolsFragment : Fragment() {

    data class Tool(val name: String, val desc: String, val icon: String, val color: Int)

    private val tools = listOf(
        Tool("终端", "命令行交互与系统控制", "⌨️", 0xFF34C759.toInt()),
        Tool("数据分析", "智能数据可视化与报告生成", "📊", 0xFFAF52DE.toInt()),
        Tool("代码审查", "代码质量分析与安全检测", "💻", 0xFF34C759.toInt()),
        Tool("网页抓取", "智能内容提取与结构化解析", "🌐", 0xFFFF9F0A.toInt()),
        Tool("安全加固", "系统防护配置与漏洞修复", "🛡️", 0xFFFF3B30.toInt()),
        Tool("文件管理", "智能文件整理与归档", "📁", 0xFF5AC8FA.toInt()),
        Tool("前端生成", "快速原型设计与页面构建", "🎨", 0xFF007AFF.toInt()),
        Tool("文档生成", "自动编写技术文档与报告", "📝", 0xFFAF52DE.toInt()),
        Tool("性能优化", "瓶颈分析与系统调优", "⚡", 0xFFFF9F0A.toInt()),
        Tool("备忘录", "快速记录想法与待办", "📋", 0xFF5AC8FA.toInt()),
        Tool("定时任务", "Cron 表达式与任务调度", "⏰", 0xFF007AFF.toInt()),
        Tool("代码解释器", "在线执行 Python 代码", "🐍", 0xFF34C759.toInt()),
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_tools, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.tools_grid)
        rv.layoutManager = GridLayoutManager(requireContext(), 2)
        rv.adapter = ToolsAdapter(tools)
    }
}
