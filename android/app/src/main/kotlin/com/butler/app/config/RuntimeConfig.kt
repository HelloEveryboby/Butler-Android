package com.butler.app.config

import android.content.Context
import android.content.SharedPreferences

/**
 * RuntimeConfig — 云端 LLM API 配置
 *
 * Android 端独立运行，直连一个云端 LLM API（OpenAI 兼容协议 /chat/completions）。
 * 持久化在 SharedPreferences，键名 "butler-runtime-config"。
 */
object RuntimeConfig {

    data class LlmConfig(
        val provider: String = "deepseek",
        val model: String = "deepseek-chat",
        val baseUrl: String = "https://api.deepseek.com/v1",
        val apiKey: String = "",
    )

    private const val PREFS_NAME = "butler-runtime-config"
    private const val KEY_LLM_PROVIDER = "llm.provider"
    private const val KEY_LLM_MODEL = "llm.model"
    private const val KEY_LLM_BASE = "llm.baseUrl"
    private const val KEY_LLM_KEY = "llm.apiKey"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun load(context: Context): LlmConfig {
        val p = prefs(context)
        return LlmConfig(
            provider = p.getString(KEY_LLM_PROVIDER, "deepseek") ?: "deepseek",
            model = p.getString(KEY_LLM_MODEL, "deepseek-chat") ?: "deepseek-chat",
            baseUrl = p.getString(KEY_LLM_BASE, "https://api.deepseek.com/v1") ?: "https://api.deepseek.com/v1",
            apiKey = p.getString(KEY_LLM_KEY, "") ?: "",
        )
    }

    @JvmStatic
    fun save(context: Context, config: LlmConfig) {
        prefs(context).edit().apply {
            putString(KEY_LLM_PROVIDER, config.provider)
            putString(KEY_LLM_MODEL, config.model)
            putString(KEY_LLM_BASE, config.baseUrl)
            putString(KEY_LLM_KEY, config.apiKey)
            apply()
        }
    }

    /** 用户可读的状态摘要，显示在设置页。 */
    @JvmStatic
    fun statusLabel(config: LlmConfig): String = "云端直连: ${config.provider}"

    /** Provider 预设：切换提供商时自动填充 BaseURL 与默认模型名。 */
    @JvmStatic
    fun providerPresets(): Map<String, Pair<String, String>> = mapOf(
        "deepseek"  to ("https://api.deepseek.com/v1" to "deepseek-chat"),
        "openai"    to ("https://api.openai.com/v1"   to "gpt-4o-mini"),
        "anthropic" to ("https://api.anthropic.com/v1" to "claude-3-5-sonnet-latest"),
        "custom"    to ("" to ""),
    )
}
