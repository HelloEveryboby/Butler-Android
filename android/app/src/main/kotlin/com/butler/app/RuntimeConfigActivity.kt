package com.butler.app

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.butler.app.config.RuntimeConfig
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText

/**
 * RuntimeConfigActivity — 云端 LLM API 配置页（MD3 原生）
 */
class RuntimeConfigActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_runtime_config)
        setSupportActionBar(findViewById(R.id.rt_toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.rt_title)
        }

        val cfg = RuntimeConfig.load(this)

        val spinner = findViewById<Spinner?>(R.id.rt_provider)
        val providers = RuntimeConfig.providerPresets().keys.toList()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)
        spinner.setSelection(providers.indexOf(cfg.provider).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val provider = providers[pos]
                val preset = RuntimeConfig.providerPresets()[provider]
                if (preset != null) {
                    findViewById<TextInputEditText?>(R.id.rt_baseurl)?.setText(preset.first)
                    findViewById<TextInputEditText?>(R.id.rt_model)?.setText(preset.second)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        findViewById<TextInputEditText?>(R.id.rt_model)?.setText(cfg.model)
        findViewById<TextInputEditText?>(R.id.rt_baseurl)?.setText(cfg.baseUrl)
        findViewById<TextInputEditText?>(R.id.rt_apikey)?.setText(cfg.apiKey)

        findViewById<MaterialButton?>(R.id.rt_save).setOnClickListener { save() }
    }

    private fun save() {
        val provider = findViewById<Spinner?>(R.id.rt_provider)?.selectedItem?.toString() ?: "deepseek"
        val model = findViewById<TextInputEditText?>(R.id.rt_model)?.text?.toString()?.trim().orEmpty()
        val baseUrl = findViewById<TextInputEditText?>(R.id.rt_baseurl)?.text?.toString()?.trim().orEmpty()
        val apiKey = findViewById<TextInputEditText?>(R.id.rt_apikey)?.text?.toString()?.trim().orEmpty()

        val cfg = RuntimeConfig.LlmConfig(provider, model, baseUrl, apiKey)
        RuntimeConfig.save(this, cfg)
        Snackbar.make(findViewById(R.id.rt_save), "已保存: ${RuntimeConfig.statusLabel(cfg)}", Snackbar.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
