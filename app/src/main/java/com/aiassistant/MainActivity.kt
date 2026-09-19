package com.aiassistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aiassistant.api.AiProvider
import com.aiassistant.api.ApiClient
import com.aiassistant.databinding.ActivityMainBinding
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedFileUri: Uri? = null
    private var selectedFileName: String = ""

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedFileUri = it
            selectedFileName = getFileName(it)
            binding.fileNameText.text = getString(R.string.file_selected, selectedFileName)
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        setupProviderSpinner()
        setupClickListeners()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type != null) {
            selectedFileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
            selectedFileUri?.let {
                selectedFileName = getFileName(it)
                binding.fileNameText.text = getString(R.string.file_selected, selectedFileName)
            }
        }
    }

    private fun setupProviderSpinner() {
        val providers = arrayOf(
            getString(R.string.openrouter),
            getString(R.string.openai),
            getString(R.string.anthropic),
            getString(R.string.local)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, providers)
        binding.spinnerProvider.setAdapter(adapter)
    }

    private fun setupClickListeners() {
        binding.btnSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf(
                "image/*",
                "application/pdf",
                "application/zip",
                "application/x-zip-compressed",
                "text/*",
                "application/octet-stream"
            ))
        }

        binding.btnSend.setOnClickListener {
            sendToAi()
        }

        binding.btnTerminal.setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
    }

    private fun sendToAi() {
        val prompt = binding.editPrompt.text.toString().trim()
        val apiKey = binding.editApiKey.text.toString().trim()
        val provider = binding.spinnerProvider.text.toString()

        if (selectedFileUri == null) {
            Toast.makeText(this, R.string.error_no_file, Toast.LENGTH_SHORT).show()
            return
        }

        if (prompt.isEmpty()) {
            Toast.makeText(this, R.string.error_no_prompt, Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.btnSend.isEnabled = false

        lifecycleScope.launch {
            try {
                val fileBytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(selectedFileUri!!)?.readBytes()
                }

                val aiProvider = when (provider) {
                    getString(R.string.openrouter) -> AiProvider.OPENROUTER
                    getString(R.string.openai) -> AiProvider.OPENAI
                    getString(R.string.anthropic) -> AiProvider.ANTHROPIC
                    else -> AiProvider.LOCAL
                }

                val response = ApiClient.sendRequest(
                    provider = aiProvider,
                    apiKey = apiKey,
                    prompt = prompt,
                    fileName = selectedFileName,
                    fileBytes = fileBytes
                )

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnSend.isEnabled = true

                    val intent = Intent(this@MainActivity, ResponseActivity::class.java)
                    intent.putExtra("response", response)
                    startActivity(intent)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnSend.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.error_api) + e.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
