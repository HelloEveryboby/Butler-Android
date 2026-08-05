package com.butler.app.ui.tools

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.butler.app.R

class ToolsAdapter(private val items: List<ToolsFragment.Tool>) :
    RecyclerView.Adapter<ToolsAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.tool_icon)
        val name: TextView = view.findViewById(R.id.tool_name)
        val desc: TextView = view.findViewById(R.id.tool_desc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tool_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        holder.icon.text = t.icon
        holder.name.text = t.name
        holder.desc.text = t.desc
    }

    override fun getItemCount(): Int = items.size
}
