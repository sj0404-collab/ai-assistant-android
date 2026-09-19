package com.aiassistant.terminal

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MenuItem
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.aiassistant.databinding.ActivityTerminalBinding

class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.webTerminal.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }

        binding.webTerminal.webViewClient = WebViewClient()
        binding.webTerminal.loadUrl("file:///android_asset/terminal.html")
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (binding.webTerminal.canGoBack()) {
            binding.webTerminal.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
