package com.butler.app.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.butler.app.R

/**
 * ChatAdapter — 对话消息列表（MD3 风格气泡）
 *
 * 用户消息右对齐（primary 色气泡），助手消息左对齐（surfaceVariant 气泡），
 * 错误消息以 error 色文本呈现。
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isError: Boolean = false,
)

class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.VH>(DIFF) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.message_text)
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).isUser) TYPE_USER else TYPE_BOT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == TYPE_USER) R.layout.item_message_user else R.layout.item_message_bot
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = getItem(position)
        holder.text.text = msg.text
        if (msg.isError) {
            holder.text.setTextColor(holder.itemView.context.getColor(R.color.butler_red))
        }
    }

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_BOT = 2

        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(o: ChatMessage, n: ChatMessage) = o.text == n.text && o.isUser == n.isUser
            override fun areContentsTheSame(o: ChatMessage, n: ChatMessage) = o == n
        }
    }
}
