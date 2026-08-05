package com.butler.app.net

import com.butler.app.config.RuntimeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ButlerHttpClient — 云端 LLM 网络层 (MD3 native UI)
 *
 *  直连一个云端 LLM API（OpenAI 兼容协议 /chat/completions）。
 */
class ButlerHttpClient(private val client: OkHttpClient = defaultClient) {

    suspend fun chat(llm: RuntimeConfig.LlmConfig, message: String): String =
        withContext(Dispatchers.IO) {
            callCloudLLM(llm, message)
        }

    private fun callCloudLLM(llm: RuntimeConfig.LlmConfig, message: String): String {
        if (llm.apiKey.isBlank()) throw RuntimeException("未配置 API Key")
        val base = llm.baseUrl.trimEnd('/')
        val body = JSONObject().apply {
            put("model", llm.model.ifBlank { "deepseek-chat" })
            put("stream", false)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                put(JSONObject().put("role", "user").put("content", message))
            })
        }
        val req = Request.Builder()
            .url("$base/chat/completions")
            .addHeader("Authorization", "Bearer ${llm.apiKey}")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                val t = res.body?.string()?.take(300) ?: ""
                throw RuntimeException("HTTP ${res.code}${if (t.isNotBlank()) ": $t" else ""}")
            }
            val data = JSONObject(res.body?.string().orEmpty())
            val content = data
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
            return content?.ifBlank { "(空响应)" } ?: "(空响应)"
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val SYSTEM_PROMPT =
            "你是 Butler，运行在 Android 设备上的智能助手。简洁、准确、有条理地回答用户问题。"

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
