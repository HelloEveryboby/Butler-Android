package com.butler.app.ui.chat

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.butler.app.R
import com.butler.app.config.RuntimeConfig
import com.butler.app.net.ButlerHttpClient
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/**
 * ChatFragment — 原生对话界面（MD3 风格气泡 + 协程网络）
 */
class ChatFragment : Fragment() {

    private lateinit var adapter: ChatAdapter
    private val httpClient = ButlerHttpClient()
    private var thinking = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ChatAdapter()

        val rv = view.findViewById<RecyclerView>(R.id.chat_messages)
        rv.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        rv.adapter = adapter

        adapter.submitList(listOf(
            ChatMessage("你好！我是 Butler。请先到「设置 → 云端 API」配置一个 LLM API，然后开始对话。", false)
        ))

        val input = view.findViewById<TextInputEditText>(R.id.chat_input)
        val sendBtn = view.findViewById<View>(R.id.chat_send)

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                sendBtn.isEnabled = !s.isNullOrBlank() && !thinking
            }
        })
        sendBtn.setOnClickListener { send() }
    }

    private fun send() {
        if (thinking) return
        val view = view ?: return
        val input = view.findViewById<TextInputEditText>(R.id.chat_input)
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return

        input.text?.clear()
        val list = adapter.currentList.toMutableList()
        list.add(ChatMessage(text, true))
        list.add(ChatMessage(getString(R.string.chat_thinking), false))
        adapter.submitList(list)
        scrollToBottom()

        thinking = true
        view.findViewById<View>(R.id.chat_send).isEnabled = false

        val llm = RuntimeConfig.load(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val reply = httpClient.chat(llm, text)
                removeThinking()
                appendBot(reply)
            } catch (e: Throwable) {
                removeThinking()
                val hint = getString(R.string.chat_error_cloud)
                appendBot("⚠️ $hint\n\n${e.message ?: e.toString()}", isError = true)
            } finally {
                thinking = false
                view.findViewById<View>(R.id.chat_send).isEnabled = true
            }
        }
    }

    private fun appendBot(text: String, isError: Boolean = false) {
        val list = adapter.currentList.toMutableList()
        list.add(ChatMessage(text, false, isError))
        adapter.submitList(list)
        scrollToBottom()
    }

    private fun removeThinking() {
        val list = adapter.currentList.toMutableList()
        if (list.isNotEmpty() && !list.last().isUser && list.last().text == getString(R.string.chat_thinking)) {
            list.removeAt(list.lastIndex)
            adapter.submitList(list)
        }
    }

    private fun scrollToBottom() {
        view?.findViewById<RecyclerView>(R.id.chat_messages)?.let { rv ->
            rv.post { rv.scrollToPosition(adapter.itemCount - 1) }
        }
    }
}
