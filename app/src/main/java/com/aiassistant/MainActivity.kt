package com.aiassistant

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.text.ifBlank
import kotlin.text.toRegex

@SuppressLint("SetJavaScriptEnabled")
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var bar: ProgressBar

    private var fileChooser: ValueCallback<Array<Uri>>? = null
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
            ?: emptyArray()
        fileChooser?.onReceiveValue(uris)
        fileChooser = null
    }

    private var pendingOpenUrl: String? = null
    private var pageReady = false
    private val mainScope = CoroutineScope(Job() + Dispatchers.Main.immediate)
    internal val ioScope = CoroutineScope(Job() + Dispatchers.IO)
    private val scope = mainScope
    private val executor = Executors.newSingleThreadExecutor()

    // Session polling
    private val SESSION_STATE_BRANCH = "session-state"
    private val SESSION_FILE = "session-hub.json"
    private var pollJob: Job? = null
    private var currentSessionUrl: String? = null

    companion object {
        const val EXTRA_SESSION_URL = "session_url"
        const val CHANNEL_ID = "session-ready"
        const val USER_AGENT_SUFFIX = "AIAssistant/1.0"
        private const val GITHUB_API = "https://api.github.com"
        private const val REPO = "sj0404-collab/ai-assistant-android"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.webView)
        bar = findViewById(R.id.progressBar)

        configureWebView()
        registerBackHandler()
        ensureNotificationChannel()

        // Check for session URL from intent (notification tap)
        val url = intent.getStringExtra(EXTRA_SESSION_URL)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.default_hub_url)
        pendingOpenUrl = url

        if (savedInstanceState == null) {
            startPolling()
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(EXTRA_SESSION_URL)?.takeIf { it.isNotBlank() }
        if (url != null) {
            pendingOpenUrl = url
            if (pageReady) loadHubUrl(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString $USER_AGENT_SUFFIX"
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        web.addJavascriptInterface(JSBridge(this), "AIBridge")

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            web.setBackgroundColor(0xFF0D0D12.toInt())
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                bar.progress = newProgress
                bar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                view: WebView?, callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileChooser?.onReceiveValue(null)
                fileChooser = callback
                return try {
                    val intent = params?.createIntent()
                    if (intent == null) {
                        fileChooser = null
                        false
                    } else {
                        filePicker.launch(intent)
                        true
                    }
                } catch (e: Exception) {
                    fileChooser = null
                    false
                }
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("WebView", "[${message.messageLevel()}] ${message.message()}")
                return super.onConsoleMessage(message)
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                // Handle file downloads from hub
                if (url.contains("/api/files/") && request.method == "GET") {
                    return null // Let WebView handle download
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // Keep internal links in WebView
                if (url.startsWith("http") && !url.contains("github.com") && !url.contains("trycloudflare.com")) {
                    return false
                }
                // Open external links in browser
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Cannot open: $url", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                if (!request.isForMainFrame) return
                showError()
            }

            override fun onPageFinished(view: WebView, url: String) {
                bar.visibility = View.GONE
                pageReady = true
                // Notify JS that Android bridge is ready
                view.evaluateJavascript("if(window.onAndroidReady)window.onAndroidReady();", null)
                // Inject hub token if available
                getSharedPrefs().getString("hub_token", "").let { token ->
                    if (token?.isNotBlank() == true) {
                        view.evaluateJavascript("window.HUB_TOKEN = ${JSONObject.quote(token)};", null)
                    }
                }
                pendingOpenUrl?.let { loadHubUrl(it) }
            }
        }

        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            downloadFile(url, contentDisposition, mimeType)
        }
    }

    private fun loadHubUrl(url: String) {
        pendingOpenUrl = null
        web.loadUrl(url)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (pollJob?.isActive == true) {
                try {
                    fetchSessionUrl().let { url ->
                        if (url != null && url != currentSessionUrl && url.isNotBlank()) {
                            currentSessionUrl = url
                            withContext(Dispatchers.Main) {
                                if (pageReady) loadHubUrl(url)
                                else pendingOpenUrl = url
                                showSessionNotification("AI Hub Ready", "Terminal session is ready", url)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AIAssistant", "Poll error: ${e.message}")
                }
                kotlinx.coroutines.delay(10_000) // Poll every 10 seconds
            }
        }
    }

    private suspend fun fetchSessionUrl(): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$GITHUB_API/repos/$REPO/contents/$SESSION_FILE?ref=$SESSION_STATE_BRANCH"
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "AIAssistant")
            val inputStream = connection.getInputStream()
            val json = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()

            val obj = JSONObject(json)
            val content = obj.getString("content").replace("\n", "")
            val decoded = String(android.util.Base64.decode(content, android.util.Base64.DEFAULT))
            val session = JSONObject(decoded)
            session.getString("url")
        } catch (e: Exception) {
            Log.w("AIAssistant", "Failed to fetch session: ${e.message}")
            null
        }
    }

    private fun showSessionNotification(title: String, body: String, url: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
            return
        }
        ensureNotificationChannel()
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SESSION_URL, url)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val tap = PendingIntent.getActivity(this, url.hashCode(), open, flags)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .addAction(0, "Open", tap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(1000 + kotlin.math.abs(url.hashCode()), builder.build())
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sessions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "AI Hub session ready"
                enableVibration(true)
            }
        )
    }

    private fun downloadFile(url: String, contentDisposition: String?, mimeType: String?) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val name = extractFilename(contentDisposition, url)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            setMimeType(mimeType ?: mimeForName(name))
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show()
    }

    private fun extractFilename(cd: String?, url: String): String {
        var name = cd?.let {
            val matcher = java.util.regex.Pattern.compile("""filename\*?=(?:UTF-8''|")?([^";]+)""")
                .matcher(it)
            if (matcher.find()) {
                matcher.group(1)?.replace("\"", "")?.trim()
            } else null
        }
        if (name.isNullOrBlank() || name.equals("download", ignoreCase = true)) {
            name = Uri.parse(url).lastPathSegment
        }
        return name?.replace("""[/\\:*?"<>|%{}]""".toRegex(), "_")
            ?.ifBlank { "download.bin" } ?: "download.bin"
    }

    internal fun mimeForName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "xz" -> "application/x-xz"
            "gz" -> "application/gzip"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "pdf" -> "application/pdf"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "txt", "md", "log" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack()
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showError() {
        web.loadUrl("file:///android_asset/error.html")
    }

    internal fun getSharedPrefs() = getSharedPreferences("ai_assistant", Context.MODE_PRIVATE)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        pollJob?.cancel()
        mainScope.coroutineContext[Job]?.cancel()
        ioScope.coroutineContext[Job]?.cancel()
        executor.shutdown()
        web.destroy()
        super.onDestroy()
    }
}

// JavaScript Bridge
class JSBridge(private val activity: MainActivity) {

    @android.webkit.JavascriptInterface
    fun downloadFile(url: String, filename: String) {
        activity.runOnUiThread {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                setMimeType(activity.mimeForName(filename))
            }
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(activity, "Downloading $filename…", Toast.LENGTH_SHORT).show()
        }
    }

    @android.webkit.JavascriptInterface
    fun shareFile(url: String, filename: String) {
        activity.runOnUiThread {
            activity.ioScope.launch {
                try {
                    val file = withContext(Dispatchers.IO) {
                        val input = java.net.URL(url).openStream()
                        val cacheFile = File(activity.cacheDir, filename)
                        cacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                        input.close()
                        cacheFile
                    }
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        activity, "${activity.packageName}.fileprovider", file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = activity.mimeForName(filename)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(intent, "Share $filename"))
                } catch (e: Exception) {
                    activity.runOnUiThread { Toast.makeText(activity, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    @android.webkit.JavascriptInterface
    fun pickFile() {
        activity.runOnUiThread {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            activity.startActivityForResult(intent, 1001)
        }
    }

    @android.webkit.JavascriptInterface
    fun uploadFile(base64: String, filename: String, token: String) {
        activity.ioScope.launch {
            try {
                val hubUrl = activity.getSharedPrefs().getString("hub_url", "") ?: return@launch
                val uploadUrl = "$hubUrl/api/files"
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val connection = java.net.URL(uploadUrl).openConnection() as javax.net.ssl.HttpsURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.setRequestProperty("x-file-name", filename)
                if (token.isNotBlank()) connection.setRequestProperty("x-hub-token", token)
                connection.outputStream.write(bytes)
                connection.outputStream.close()
                val code = connection.responseCode
                activity.runOnUiThread {
                    if (code == 200) Toast.makeText(activity, "Uploaded $filename", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(activity, "Upload failed: $code", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                activity.runOnUiThread { Toast.makeText(activity, "Upload error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    @android.webkit.JavascriptInterface
    fun saveToken(token: String) {
        activity.getSharedPrefs().edit().putString("hub_token", token).apply()
    }

    @android.webkit.JavascriptInterface
    fun getToken(): String {
        return activity.getSharedPrefs().getString("hub_token", "") ?: ""
    }

    @android.webkit.JavascriptInterface
    fun saveHubUrl(url: String) {
        activity.getSharedPrefs().edit().putString("hub_url", url).apply()
    }

    @android.webkit.JavascriptInterface
    fun getHubUrl(): String {
        return activity.getSharedPrefs().getString("hub_url", "") ?: ""
    }

    @android.webkit.JavascriptInterface
    fun showToast(message: String) {
        activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
    }
}