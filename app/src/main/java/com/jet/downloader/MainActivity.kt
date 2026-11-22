package com.jet.downloader

import android.Manifest
import android.app.DownloadManager
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.jet.downloader.data.DownloadDao
import com.jet.downloader.data.DownloadEntity
import com.jet.downloader.data.PreferencesManager
import com.jet.downloader.model.DownloadCompletePayload
import com.jet.downloader.model.DownloadErrorPayload
import com.jet.downloader.model.LibraryItemPayload
import com.jet.downloader.model.MediaInfoPayload
import com.jet.downloader.model.StreamSizePayload
import com.jet.downloader.model.OpenSourceLicenseItem
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.koin.android.ext.android.inject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import com.jet.downloader.network.JetOkHttpDownloader
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@androidx.media3.common.util.UnstableApi
class MainActivity : AppCompatActivity() {

    private val okHttpClient: OkHttpClient by inject()
    private val newPipeDownloader: JetOkHttpDownloader by inject()
    private val newPipeInitLock = Any()
    private val downloadDao: DownloadDao by inject()
    private val preferencesManager: PreferencesManager by inject()
    private val json: Json by inject()

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var navUi: NavUi
    private lateinit var media3Overlay: LinearLayout
    private lateinit var media3TitleView: TextView
    private lateinit var media3PlayerView: PlayerView
    private var exoPlayer: ExoPlayer? = null

    private var pendingSharedUrl: String? = null
    private var lastDeliveredClipboardUrl: String? = null
    private var currentPlayingPath: String = ""

    @Volatile
    private var ytDlpReady: Boolean = false
    // Serializes yt-dlp initialization, extraction, downloads and updates.
    private val ytDlpEngineLock = Any()

    private fun ensureYtDlpInitialized() {
        synchronized(ytDlpEngineLock) {
            if (!ytDlpReady) {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                ytDlpReady = true
            }
        }
    }

    private fun isEngineUpdateRequired(error: Throwable): Boolean {
        val messages = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase(Locale.US)
        val patterns = listOf(
            "http error 400", "http 400", "precondition check failed",
            "po token", "po_token", "potoken", "nsig extraction failed",
            "signature extraction failed", "please update yt-dlp",
            "sign in to confirm you're not a bot"
        )
        return patterns.any { messages.contains(it) }
    }

    private fun notifyEngineUpdateRequired(error: Throwable) {
        if (isEngineUpdateRequired(error)) {
            callJs("onNativeEngineUpdateRequired", JSONObject.quote(error.message ?: "Engine update required"))
        }
    }

    // Reuse extracted stream metadata across prefetch and modal-open requests.
    private val streamInfoCache = LruCache<String, StreamInfo>(8)
    private val streamInfoInFlight = ConcurrentHashMap<String, CompletableDeferred<StreamInfo>>()

    private suspend fun getOrFetchStreamInfo(url: String): StreamInfo {
        synchronized(streamInfoCache) {
            streamInfoCache.get(url)?.let { return it }
        }
        val pending = CompletableDeferred<StreamInfo>()
        val existing = streamInfoInFlight.putIfAbsent(url, pending)
        if (existing != null) return existing.await()
        try {
            val info = extractStreamInfoWithReloadRetry(url)
            synchronized(streamInfoCache) { streamInfoCache.put(url, info) }
            pending.complete(info)
            return info
        } catch (error: Exception) {
            pending.completeExceptionally(error)
            throw error
        } finally {
            streamInfoInFlight.remove(url, pending)
        }
    }

    private fun isPageReloadRequired(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current.message.orEmpty().contains("page needs to be reloaded", ignoreCase = true)) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private suspend fun extractStreamInfoWithReloadRetry(url: String): StreamInfo {
        return try {
            StreamInfo.getInfo(url)
        } catch (firstError: Exception) {
            if (!isPageReloadRequired(firstError)) throw firstError

            // The extractor's site configuration/session can become stale. Reinitialize
            // its downloader once and retry the same URL before using the yt-dlp fallback.
            synchronized(newPipeInitLock) {
                NewPipe.init(newPipeDownloader)
            }
            synchronized(streamInfoCache) { streamInfoCache.remove(url) }
            StreamInfo.getInfo(url)
        }
    }

    private val streamSizeCache = ConcurrentHashMap<String, Long>()
    // Keep googlevideo range probes below the concurrency level that commonly triggers 403s.
    private val streamProbeSemaphore = java.util.concurrent.Semaphore(2)

    // Must match JetOkHttpDownloader's User-Agent.
    private val extractorUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    /** Called from Dispatchers.IO. Resolves URL clen, then a one-byte range response, then bitrate. */
    private fun estimateStreamBytes(
        contentUrl: String,
        bitrateBitsPerSecond: Long,
        durationSeconds: Long
    ): Long {
        if (contentUrl.isBlank()) return 0L
        streamSizeCache[contentUrl]?.let { return it }

        val clenBytes = try {
            Uri.parse(contentUrl).getQueryParameter("clen")?.toLongOrNull()?.takeIf { it > 0L }
        } catch (_: Exception) {
            null
        }
        if (clenBytes != null) {
            streamSizeCache[contentUrl] = clenBytes
            return clenBytes
        }

        val rangedBytes = try {
            streamProbeSemaphore.acquire()
            try {
                val request = okhttp3.Request.Builder()
                    .url(contentUrl)
                    .header("User-Agent", extractorUserAgent)
                    .header("Referer", "https://www.youtube.com/")
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=0-0")
                    .get()
                    .build()

                okHttpClient.newBuilder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(7, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response ->
                        val totalFromRange = response.header("Content-Range")
                            ?.substringAfterLast('/', "")
                            ?.trim()
                            ?.toLongOrNull()
                            ?.takeIf { it > 0L }

                        when {
                            totalFromRange != null -> totalFromRange
                            // A 206 response's Content-Length is usually just the one requested byte.
                            response.code == 200 -> response.header("Content-Length")
                                ?.toLongOrNull()
                                ?.takeIf { it > 1L }
                            else -> null
                        }
                    }
            } finally {
                streamProbeSemaphore.release()
            }
        } catch (_: Exception) {
            null
        }

        if (rangedBytes != null && rangedBytes > 0L) {
            streamSizeCache[contentUrl] = rangedBytes
            return rangedBytes
        }

        // Bitrate fallback covers HLS and hosts that do not expose a total byte count.
        val estimatedBytes = if (bitrateBitsPerSecond > 0L && durationSeconds > 0L) {
            try {
                Math.multiplyExact(bitrateBitsPerSecond, durationSeconds) / 8L
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }
        } else {
            0L
        }
        if (estimatedBytes > 0L) streamSizeCache[contentUrl] = estimatedBytes
        return estimatedBytes
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permissions handled */ }

    @SuppressLint("SetJavaScriptEnabled", "AllowFileAccessFromFileURLs")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRuntimePermissions()
        initializeFallbackEngine()

        rootContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer.addView(webView)
        navUi = NavUiFactory.create()
        navUi.attach(this, rootContainer, webView)
        setupNativeMedia3PlayerOverlay()
        setContentView(rootContainer)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        webView.addJavascriptInterface(JetNativeBridge(), "JetAndroid")
        webView.addJavascriptInterface(JetNetworkBridge(), "JetNetwork")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                navUi.onPageLoaded()
                syncDataStorePreferencesToWeb()
                notifyEngineState()
                pendingSharedUrl?.let { shared ->
                    callJs("onNativeSharedUrl", JSONObject.quote(shared))
                    pendingSharedUrl = null
                }
                dispatchClipboardUrlToWebView()
                pushScannedLibraryToWeb()
                syncNetworkStateToJs()
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && request.url.scheme != "file") {
                    callJs("onNetworkStateChanged", "false")
                }
            }
        }

        networkMonitor = NetworkMonitor(applicationContext) { online ->
            syncNetworkStateToJs(online)
        }
        handleIncomingIntent(intent)
        webView.clearCache(true)
        webView.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (media3Overlay.visibility == View.VISIBLE) {
                    closeMedia3Player()
                    return
                }
                webView.evaluateJavascript(
                    "(function(){ return window.handleAndroidBack ? window.handleAndroidBack() : false; })()"
                ) { result ->
                    if (result != "true" && !navUi.handleBack()) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private lateinit var networkMonitor: NetworkMonitor

    private inner class JetNetworkBridge {
        @JavascriptInterface
        fun isOnline(): Boolean = if (::networkMonitor.isInitialized) networkMonitor.isOnline() else false
    }

    private fun syncNetworkStateToJs(online: Boolean? = null) {
        if (!::webView.isInitialized) return
        val current = online ?: if (::networkMonitor.isInitialized) networkMonitor.isOnline() else false
        webView.post {
            if (::webView.isInitialized) {
                webView.evaluateJavascript("if (window.onNetworkStateChanged) window.onNetworkStateChanged($current);", null)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::networkMonitor.isInitialized) networkMonitor.start()
    }

    override fun onStop() {
        if (::networkMonitor.isInitialized) networkMonitor.stop()
        super.onStop()
    }

    private fun setupNativeMedia3PlayerOverlay() {
        media3Overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5050811"))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 48, 32, 24)
        }

        val closeBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener { closeMedia3Player() }
        }

        media3TitleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            setPadding(24, 0, 24, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val extBtn = TextView(this).apply {
            text = "External"
            setTextColor(Color.parseColor("#60A5FA"))
            textSize = 13f
            setPadding(16, 12, 16, 12)
            setOnClickListener {
                if (currentPlayingPath.isNotEmpty()) {
                    openFileInExternalApp(currentPlayingPath)
                }
            }
        }

        topBar.addView(closeBtn)
        topBar.addView(media3TitleView)
        topBar.addView(extBtn)

        media3PlayerView = PlayerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            useController = true
        }

        media3Overlay.addView(topBar)
        media3Overlay.addView(media3PlayerView)
        rootContainer.addView(media3Overlay)
    }

    private fun playWithMedia3(title: String, filePath: String) {
        runOnUiThread {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(this, "File not found on storage", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            currentPlayingPath = filePath
            media3TitleView.text = title
            media3Overlay.visibility = View.VISIBLE

            val player = exoPlayer ?: ExoPlayer.Builder(this).build().also {
                exoPlayer = it
                media3PlayerView.player = it
            }
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.playWhenReady = true
        }
    }

    private fun closeMedia3Player() {
        exoPlayer?.stop()
        media3Overlay.visibility = View.GONE
    }

    override fun onDestroy() {
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }

    private fun syncDataStorePreferencesToWeb() {
        lifecycleScope.launch {
            val isDark = preferencesManager.darkModeFlow.first()
            val clipEnabled = preferencesManager.clipboardDetectFlow.first()
            callJs("onNativePreferencesLoaded", "$isDark, $clipEnabled")
        }
    }

    private fun initializeFallbackEngine() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ensureYtDlpInitialized()
                // Best-effort silent refresh; keep the installed engine if offline.
                try {
                    synchronized(ytDlpEngineLock) {
                        YoutubeDL.getInstance().updateYoutubeDL(
                            applicationContext,
                            YoutubeDL.UpdateChannel.STABLE
                        )
                    }
                } catch (_: Exception) {
                    // Network/update failures must not prevent app startup.
                }
                withContext(Dispatchers.Main) {
                    if (::webView.isInitialized) notifyEngineState()
                }
            } catch (_: Exception) {
                ytDlpReady = false
            }
        }
    }

    private fun notifyEngineState() {
        callJs("onNativeEngineReady", "true, ${JSONObject.quote("NewPipe v0.24.2 + Media3")}")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.postDelayed({
                dispatchClipboardUrlToWebView()
                pushScannedLibraryToWeb()
            }, 350)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            if (!sharedText.isNullOrEmpty()) {
                val extracted = extractFirstUrl(sharedText) ?: sharedText
                if (::webView.isInitialized && webView.url != null) {
                    callJs("onNativeSharedUrl", JSONObject.quote(extracted))
                } else {
                    pendingSharedUrl = extracted
                }
            }
        }
    }

    private fun dispatchClipboardUrlToWebView() {
        try {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val rawText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            val matchedUrl = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).find(rawText)?.value ?: return
            if (matchedUrl == lastDeliveredClipboardUrl) return

            val quoted = org.json.JSONObject.quote(matchedUrl)
            val js = """
                (function(u){
                  if (typeof window.onNativeClipboardUrl === 'function') { window.onNativeClipboardUrl(u); return 'DELIVERED'; }
                  if (typeof window.onClipboardLinkDetected === 'function') { window.onClipboardLinkDetected(u); return 'DELIVERED'; }
                  if (typeof window.handleClipboardUrl === 'function') { window.handleClipboardUrl(u); return 'DELIVERED'; }
                  return 'NOT_READY';
                })($quoted);
            """.trimIndent()
            webView.evaluateJavascript(js) { res ->
                if (res != null && res.contains("DELIVERED")) {
                    lastDeliveredClipboardUrl = matchedUrl
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isFinishing && ::webView.isInitialized) {
            webView.postDelayed({ dispatchClipboardUrlToWebView() }, 250L)
            webView.postDelayed({ dispatchClipboardUrlToWebView() }, 900L)
        }
    }

    private fun extractFirstUrl(text: String): String? {
        val regex = Regex(
            "(?i)(https?://)?(?:www\\.|m\\.|music\\.)?(?:youtube\\.com|youtu\\.be|facebook\\.com|fb\\.watch|instagram\\.com|tiktok\\.com)/[^\\s<>\\\"']+"
        )
        val match = regex.find(text)?.value ?: return null
        val cleanUrl = match.trimEnd('.', ',', ';', ')', ']', '}')
        return if (cleanUrl.startsWith("http://", true) || cleanUrl.startsWith("https://", true)) {
            cleanUrl
        } else {
            "https://$cleanUrl"
        }
    }

    private fun callJs(fnName: String, rawArgs: String) {
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript("if (window.$fnName) window.$fnName($rawArgs);", null)
            }
        }
    }

    private fun getJetDownloadDir(): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val jetDir = File(publicDownloads, "JET")
        if (!jetDir.exists()) jetDir.mkdirs()
        return if (jetDir.exists() && jetDir.canWrite()) {
            jetDir
        } else {
            val fallback = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "JET")
            if (!fallback.exists()) fallback.mkdirs()
            fallback
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "Auto"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    private fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return "0:00"
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(Locale.US, "%d:%02d", mins, secs)
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim().take(80).ifEmpty { "JET_Media" }
    }

    private fun pushScannedLibraryToWeb() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = getJetDownloadDir()
                val files = dir.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()

                val items = files.map { f ->
                    val lower = f.name.lowercase(Locale.US)
                    val type = when {
                        lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".opus") || lower.endsWith(".wav") -> "audio"
                        lower.endsWith(".pdf") || lower.endsWith(".txt") || lower.endsWith(".zip") -> "document"
                        else -> "video"
                    }
                    val ext = f.extension.uppercase(Locale.US).ifEmpty { "FILE" }
                    LibraryItemPayload(
                        id = f.name.hashCode(),
                        title = f.nameWithoutExtension,
                        type = type,
                        meta = "${formatBytes(f.length())} · $ext",
                        duration = ext,
                        filePath = f.absolutePath,
                        fileUri = Uri.fromFile(f).toString()
                    )
                }
                callJs("onNativeLibraryList", json.encodeToString(items))
            } catch (_: Exception) {
            }
        }
    }

    private fun openFileInExternalApp(filePath: String) {
        runOnUiThread {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    Toast.makeText(this, "File not found on storage", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val uri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    file
                )
                val lower = file.name.lowercase(Locale.US)
                val mime = when {
                    lower.endsWith(".mp3") || lower.endsWith(".m4a") -> "audio/*"
                    lower.endsWith(".pdf") -> "application/pdf"
                    else -> "video/*"
                }
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(viewIntent, "Open with"))
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    inner class JetNativeBridge {
        @JavascriptInterface
        fun onScreenChanged(target: String) {
            runOnUiThread {
                if (::navUi.isInitialized) navUi.onScreenChanged(target)
            }
        }


        @JavascriptInterface
        fun setStatusBarTheme(isDark: Boolean) {
            lifecycleScope.launch {
                preferencesManager.setDarkMode(isDark)
            }
            runOnUiThread {
                val window = this@MainActivity.window
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (isDark) {
                    window.statusBarColor = Color.parseColor("#090C14")
                    window.navigationBarColor = Color.parseColor("#0D121F")
                    controller.isAppearanceLightStatusBars = false
                    controller.isAppearanceLightNavigationBars = false
                } else {
                    window.statusBarColor = Color.parseColor("#F4F6FB")
                    window.navigationBarColor = Color.parseColor("#FFFFFF")
                    controller.isAppearanceLightStatusBars = true
                    controller.isAppearanceLightNavigationBars = true
                }
            }
        }

        @JavascriptInterface
        fun setClipboardAutoDetect(enabled: Boolean) {
            lifecycleScope.launch {
                preferencesManager.setClipboardDetect(enabled)
            }
        }

        @JavascriptInterface
        fun getClipboardUrl(): String {
            return try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
                extractFirstUrl(text) ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun getOpenSourceLicensesJson(): String {
            val list = listOf(
                OpenSourceLicenseItem(
                    name = "NewPipe Extractor",
                    license = "GPLv3",
                    category = "Media Stream Extraction",
                    description = "Extracts stream metadata, audio, and video URLs from streaming services.",
                    url = "https://github.com/TeamNewPipe/NewPipeExtractor"
                ),
                OpenSourceLicenseItem(
                    name = "AndroidX Media3",
                    license = "Apache 2.0",
                    category = "ExoPlayer & Media Playback",
                    description = "Hardware-accelerated video and audio playback engine for Android.",
                    url = "https://developer.android.com/jetpack/androidx/releases/media3"
                ),
                OpenSourceLicenseItem(
                    name = "AndroidX Room",
                    license = "Apache 2.0",
                    category = "Local SQLite Persistence",
                    description = "Structured database storage for download history and metadata.",
                    url = "https://developer.android.com/jetpack/androidx/releases/room"
                ),
                OpenSourceLicenseItem(
                    name = "AndroidX Navigation",
                    license = "Apache 2.0",
                    category = "App Navigation Architecture",
                    description = "Consistent navigation and back-stack handling across screens.",
                    url = "https://developer.android.com/jetpack/androidx/releases/navigation"
                ),
                OpenSourceLicenseItem(
                    name = "AndroidX DataStore",
                    license = "Apache 2.0",
                    category = "Preferences Storage",
                    description = "Asynchronous key-value storage for theme and app settings.",
                    url = "https://developer.android.com/jetpack/androidx/releases/datastore"
                ),
                OpenSourceLicenseItem(
                    name = "Koin",
                    license = "Apache 2.0",
                    category = "Dependency Injection",
                    description = "Lightweight pragmatic dependency injection framework for Kotlin.",
                    url = "https://insert-koin.io"
                ),
                OpenSourceLicenseItem(
                    name = "Coil",
                    license = "Apache 2.0",
                    category = "Image Loading",
                    description = "Coroutine-backed image and thumbnail loading library for Android.",
                    url = "https://coil-kt.github.io/coil/"
                ),
                OpenSourceLicenseItem(
                    name = "OkHttp",
                    license = "Apache 2.0",
                    category = "HTTP & Stream Networking",
                    description = "Efficient HTTP/2 client powering NewPipe Extractor and stream downloads.",
                    url = "https://square.github.io/okhttp/"
                ),
                OpenSourceLicenseItem(
                    name = "Kotlin Coroutines & Serialization",
                    license = "Apache 2.0",
                    category = "Concurrency & JSON Parsing",
                    description = "Structured asynchronous concurrency and multiplatform JSON serialization.",
                    url = "https://github.com/Kotlin/kotlinx.coroutines"
                )
            )
            return json.encodeToString(list)
        }

        @JavascriptInterface
        fun prefetchMediaInfo(url: String) {
            if (!url.startsWith("http")) return
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    getOrFetchStreamInfo(url)
                } catch (_: Exception) {
                    // The foreground fetch reports errors when the user opens the format modal.
                }
            }
        }

        @JavascriptInterface
        fun fetchMediaInfo(url: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // A prefetched StreamInfo is reused; concurrent requests share one extraction.
                    val info = getOrFetchStreamInfo(url)
                    val bestThumb = info.thumbnails.maxByOrNull { it.width }?.url ?: ""
                    val durationSec = info.duration.toLong()
                    val bestAudio = info.audioStreams
                        .filter { it.isUrl }
                        .maxByOrNull { it.averageBitrate }

                    val bestAudioBytesJob = bestAudio?.let { audio ->
                        async(Dispatchers.IO) {
                            // NewPipe reports averageBitrate in kbps; estimateStreamBytes expects bits/sec.
                            estimateStreamBytes(audio.content, audio.averageBitrate * 1000L, durationSec)
                        }
                    }

                    fun sizeText(bytes: Long): String =
                        if (bytes > 0L) String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0)) else "Approx. unavailable"

                    fun shortSide(width: Int, height: Int): Int =
                        if (width > 0) minOf(width, height) else height

                    val streamSizes = mutableMapOf<String, StreamSizePayload>()
                    listOf(
                        "aud-70" to 70L,
                        "aud-128" to 128L,
                        "aud-160" to 160L,
                        "aud-320" to 320L
                    ).forEach { (id, bitrateKbps) ->
                        val estimatedMp3Bytes = (bitrateKbps * 1000L * durationSec) / 8L
                        streamSizes[id] = StreamSizePayload(sizeText = sizeText(estimatedMp3Bytes))
                    }

                    // Use the smallest video dimension as the resolution class. This supports
                    // landscape, cinematic and portrait/Shorts formats without height equality checks.
                    val videoSizeJobs = listOf(
                        "vid-144" to 144,
                        "vid-240" to 240,
                        "vid-360" to 360,
                        "vid-480" to 480,
                        "vid-720" to 720,
                        "vid-1080" to 1080,
                        "vid-2k" to 1440,
                        "vid-4k" to 2160
                    ).map { (id, targetShortSide) ->
                        id to async(Dispatchers.IO) {
                            val video = info.videoStreams
                                .filter {
                                    it.isUrl && shortSide(it.width, it.height) in 1..targetShortSide
                                }
                                .maxWithOrNull(
                                    compareBy<org.schabi.newpipe.extractor.stream.VideoStream>(
                                        { shortSide(it.width, it.height) },
                                        { it.bitrate }
                                    )
                                )
                            video?.let {
                                estimateStreamBytes(it.content, it.bitrate.toLong(), durationSec) to it.isVideoOnly
                            } ?: (0L to false)
                        }
                    }

                    val bestAudioBytes = bestAudioBytesJob?.await() ?: 0L
                    streamSizes["aud-fast"] = StreamSizePayload(sizeText = sizeText(bestAudioBytes))
                    videoSizeJobs.forEach { (id, job) ->
                        val (videoBytes, isVideoOnly) = job.await()
                        val totalBytes = if (isVideoOnly) videoBytes + bestAudioBytes else videoBytes
                        streamSizes[id] = StreamSizePayload(sizeText = sizeText(totalBytes))
                    }

                    val payload = MediaInfoPayload(
                        ok = true,
                        title = info.name ?: "JET Media Stream",
                        duration = formatDuration(info.duration.toInt()),
                        thumbnail = bestThumb,
                        uploader = info.uploaderName ?: "",
                        extractor = "NewPipe Extractor",
                        streams = streamSizes
                    )
                    callJs("onNativeMediaInfo", json.encodeToString(payload))
                } catch (npError: Exception) {
                    // 2. Fallback to yt-dlp for sites outside NewPipe Extractor
                    try {
                        ensureYtDlpInitialized()
                        val metadataRequest = YoutubeDLRequest(url).apply {
                            addOption("--dump-single-json")
                            addOption("--no-playlist")
                            addOption("--no-warnings")
                            addOption("--extractor-args", "youtube:player_client=ios,android,web")
                        }
                        val info = synchronized(ytDlpEngineLock) {
                            YoutubeDL.getInstance().getInfo(metadataRequest)
                        }
                        val durationSec = info.duration.toLong()
                        val formats = info.formats.orEmpty()

                        fun formatBytes(
                            exactBytes: Long,
                            approximateBytes: Long,
                            bitrateKbps: Double,
                            durationSec: Long
                        ): Long {
                            if (exactBytes > 0L) return exactBytes
                            if (approximateBytes > 0L) return approximateBytes
                            if (bitrateKbps > 0.0 && durationSec > 0L) {
                                return (bitrateKbps * 1000.0 * durationSec / 8.0).toLong()
                            }
                            return 0L
                        }

                        fun sizeLabel(bytes: Long): String =
                            if (bytes > 0L) String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
                            else "Approx. unavailable"

                        fun shortSide(width: Int, height: Int): Int =
                            if (width > 0) minOf(width, height) else height

                        val audioFormats = formats.filter {
                            it.acodec != null && it.acodec != "none" &&
                                (it.vcodec == null || it.vcodec == "none") &&
                                !it.url.isNullOrBlank()
                        }
                        val bestAudioFormat = audioFormats.maxByOrNull {
                            (it.abr.takeIf { bitrate -> bitrate > 0 } ?: it.tbr).toLong()
                        }
                        val bestAudioBytes = bestAudioFormat?.let {
                            formatBytes(
                                it.fileSize,
                                it.fileSizeApproximate,
                                (it.abr.takeIf { bitrate -> bitrate > 0 } ?: it.tbr).toDouble(),
                                durationSec
                            )
                        } ?: (320.0 * 1000.0 * durationSec.coerceAtLeast(0L).toDouble() / 8.0).toLong()

                        val streamSizes = mutableMapOf<String, StreamSizePayload>()
                        listOf(
                            "aud-70" to 70L,
                            "aud-128" to 128L,
                            "aud-160" to 160L,
                            "aud-320" to 320L
                        ).forEach { (id, targetKbps) ->
                            val audioFormat = audioFormats.minByOrNull {
                                kotlin.math.abs(
                                    (it.abr.takeIf { bitrate -> bitrate > 0 } ?: it.tbr).toLong() - targetKbps
                                )
                            }
                            val bytes = audioFormat?.let {
                                formatBytes(
                                    it.fileSize,
                                    it.fileSizeApproximate,
                                    (it.abr.takeIf { bitrate -> bitrate > 0 } ?: it.tbr).toDouble(),
                                    durationSec
                                )
                            } ?: (targetKbps.toDouble() * 1000.0 * durationSec.coerceAtLeast(0L).toDouble() / 8.0).toLong()
                            streamSizes[id] = StreamSizePayload(
                                sizeText = sizeLabel(bytes)
                            )
                        }
                        streamSizes["aud-fast"] = StreamSizePayload(
                            sizeText = sizeLabel(bestAudioBytes)
                        )

                        listOf(
                            "vid-144" to 144,
                            "vid-240" to 240,
                            "vid-360" to 360,
                            "vid-480" to 480,
                            "vid-720" to 720,
                            "vid-1080" to 1080,
                            "vid-2k" to 1440,
                            "vid-4k" to 2160
                        ).forEach { (id, targetShortSide) ->
                            val videoFormat = formats
                                .filter {
                                    it.vcodec != null && it.vcodec != "none" &&
                                        !it.url.isNullOrBlank() &&
                                        shortSide(it.width, it.height) in 1..targetShortSide
                                }
                                .maxWithOrNull(
                                    compareBy< com.yausername.youtubedl_android.mapper.VideoFormat>(
                                        { shortSide(it.width, it.height) },
                                        { it.tbr }
                                    )
                                )
                            val videoBytes = videoFormat?.let {
                                formatBytes(
                                    it.fileSize,
                                    it.fileSizeApproximate,
                                    it.tbr.toDouble(),
                                    durationSec
                                )
                            } ?: 0L
                            val isVideoOnly = videoFormat != null && (videoFormat.acodec == null || videoFormat.acodec == "none")
                            val totalBytes = if (isVideoOnly) videoBytes + bestAudioBytes else videoBytes
                            streamSizes[id] = StreamSizePayload(
                                sizeText = sizeLabel(totalBytes)
                            )
                        }

                        val payload = MediaInfoPayload(
                            ok = true,
                            title = info.title ?: "JET Media Stream",
                            duration = formatDuration(info.duration),
                            thumbnail = info.thumbnail ?: "",
                            uploader = info.uploader ?: "",
                            extractor = "yt-dlp Fallback",
                            streams = streamSizes
                        )
                        callJs("onNativeMediaInfo", json.encodeToString(payload))
                    } catch (e: Exception) {
                        notifyEngineUpdateRequired(e)
                        notifyEngineUpdateRequired(npError)
                        val errPayload = MediaInfoPayload(
                            ok = false,
                            error = npError.message ?: e.message ?: "Unable to parse URL"
                        )
                        callJs("onNativeMediaInfo", json.encodeToString(errPayload))
                    }
                }
            }
        }

        @JavascriptInterface
        fun startRealDownload(
            downloadId: String,
            url: String,
            title: String,
            isAudio: Boolean,
            audioBitrate: String,
            audioExt: String,
            maxHeight: Int,
            includeSubtitles: Boolean
        ) {
            lifecycleScope.launch(Dispatchers.IO) {
                val outDir = getJetDownloadDir()

                try {
                    // Fast direct stream download via NewPipe Extractor + OkHttp when muxing isn't needed
                    var downloadedViaNewPipe = false
                    if (!includeSubtitles && (isAudio && audioExt.equals("m4a", ignoreCase = true) || (!isAudio && maxHeight <= 720))) {
                        try {
                            callJs("onNativeDownloadProgress", "${JSONObject.quote(downloadId)}, 8, ${JSONObject.quote("NewPipe Extractor...")}")
                            val info = getOrFetchStreamInfo(url)
                            val safeTitle = sanitizeFileName(info.name ?: title)

                            val targetStreamUrl: String?
                            val outFile: File
                            if (isAudio) {
                                val audioStream = info.audioStreams.maxByOrNull { it.averageBitrate }
                                targetStreamUrl = audioStream?.content
                                outFile = File(outDir, "$safeTitle.m4a")
                            } else {
                                val muxedStream = info.videoStreams
                                    .filter { it.height <= maxHeight && it.isUrl }
                                    .maxByOrNull { it.height }
                                targetStreamUrl = muxedStream?.content
                                outFile = File(outDir, "$safeTitle (${muxedStream?.height ?: maxHeight}p).mp4")
                            }

                            if (!targetStreamUrl.isNullOrBlank()) {
                                val req = okhttp3.Request.Builder()
                                    .url(targetStreamUrl)
                                    .header("User-Agent", "Mozilla/5.0")
                                    .build()
                                okHttpClient.newCall(req).execute().use { resp ->
                                    if (resp.isSuccessful && resp.body != null) {
                                        val body = resp.body!!
                                        val totalBytes = body.contentLength()
                                        var bytesReadTotal = 0L
                                        var lastReportTime = System.currentTimeMillis()
                                        var lastBytes = 0L

                                        body.byteStream().use { input ->
                                            FileOutputStream(outFile).use { output ->
                                                val buffer = ByteArray(16 * 1024)
                                                var read: Int
                                                while (input.read(buffer).also { read = it } != -1) {
                                                    output.write(buffer, 0, read)
                                                    bytesReadTotal += read
                                                    val now = System.currentTimeMillis()
                                                    if (now - lastReportTime >= 450) {
                                                        val speedBps = ((bytesReadTotal - lastBytes) * 1000.0) / (now - lastReportTime)
                                                        val speedStr = "${formatBytes(speedBps.toLong())}/s"
                                                        val pct = if (totalBytes > 0) ((bytesReadTotal * 100) / totalBytes).toInt().coerceIn(5, 99) else 50
                                                        callJs("onNativeDownloadProgress", "${JSONObject.quote(downloadId)}, $pct, ${JSONObject.quote(speedStr)}")
                                                        lastReportTime = now
                                                        lastBytes = bytesReadTotal
                                                    }
                                                }
                                            }
                                        }
                                        if (outFile.exists() && outFile.length() > 0L) {
                                            finishDownloadSuccess(downloadId, url, outFile, if (isAudio) "audio" else "video")
                                            downloadedViaNewPipe = true
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            downloadedViaNewPipe = false
                        }
                    }

                    if (downloadedViaNewPipe) return@launch

                    // Adaptive 1080p/2K/4K FFmpeg muxing or MP3 conversion via yt-dlp + FFmpeg
                    ensureYtDlpInitialized()

                    val beforeFiles = outDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()
                    val request = YoutubeDLRequest(url)
                    request.addOption("-o", "${outDir.absolutePath}/%(title).80B [%(id)s].%(ext)s")
                    request.addOption("--no-mtime")
                    request.addOption("--no-playlist")
                    request.addOption("--extractor-args", "youtube:player_client=ios,android,web")

                    if (isAudio) {
                        request.addOption("-x")
                        request.addOption("--audio-format", audioExt.lowercase(Locale.US))
                        request.addOption("--audio-quality", audioBitrate)
                    } else {
                        val selector = "bestvideo[height<=${maxHeight}][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=${maxHeight}]+bestaudio/best[height<=${maxHeight}]/best"
                        request.addOption("-f", selector)
                        request.addOption("--merge-output-format", "mp4")
                        if (includeSubtitles) {
                            request.addOption("--write-auto-subs")
                            request.addOption("--sub-lang", "en.*")
                        }
                    }

                    val speedRegex = Regex("at\\s+([0-9.]+\\s*[KMG]i?B/s)")
                    synchronized(ytDlpEngineLock) {
                        YoutubeDL.getInstance().execute(request, "jet_dl_$downloadId") { progress, etaInSeconds, line ->
                        val pct = if (progress >= 0f) progress.toInt().coerceIn(0, 99) else 5
                        val speedMatch = speedRegex.find(line)?.groupValues?.getOrNull(1)
                        val speedLabel = when {
                            speedMatch != null -> "$speedMatch · ETA ${etaInSeconds}s"
                            line.contains("Merging", ignoreCase = true) || line.contains("ExtractAudio", ignoreCase = true) -> "FFmpeg processing..."
                            else -> "Downloading..."
                        }
                            callJs("onNativeDownloadProgress", "${JSONObject.quote(downloadId)}, $pct, ${JSONObject.quote(speedLabel)}")
                        }
                    }

                    val afterFiles = outDir.listFiles()
                        ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                        ?: emptyList()
                    val newFile = afterFiles
                        .filter { it.absolutePath !in beforeFiles }
                        .maxByOrNull { it.lastModified() }
                        ?: afterFiles.maxByOrNull { it.lastModified() }

                    if (newFile != null && newFile.exists()) {
                        finishDownloadSuccess(downloadId, url, newFile, if (isAudio) "audio" else "video")
                    } else {
                        val fail = DownloadErrorPayload(downloadId, "Output file not found after download")
                        callJs("onNativeDownloadError", json.encodeToString(fail))
                    }
                } catch (e: Exception) {
                    notifyEngineUpdateRequired(e)
                    val fail = DownloadErrorPayload(downloadId, e.message ?: "Download failed")
                    callJs("onNativeDownloadError", json.encodeToString(fail))
                }
            }
        }

        private suspend fun finishDownloadSuccess(
            downloadId: String,
            url: String,
            file: File,
            mediaType: String
        ) {
            MediaScannerConnection.scanFile(applicationContext, arrayOf(file.absolutePath), null, null)
            val sizeStr = formatBytes(file.length())

            // Persist in Room database
            downloadDao.upsert(
                DownloadEntity(
                    id = downloadId,
                    title = file.nameWithoutExtension,
                    url = url,
                    formatLabel = file.extension.uppercase(Locale.US),
                    filePath = file.absolutePath,
                    fileSize = sizeStr,
                    mediaType = mediaType
                )
            )

            val payload = DownloadCompletePayload(
                id = downloadId,
                title = file.nameWithoutExtension,
                size = sizeStr,
                filePath = file.absolutePath,
                fileUri = Uri.fromFile(file).toString()
            )
            callJs("onNativeDownloadComplete", json.encodeToString(payload))
            pushScannedLibraryToWeb()
        }

        @JavascriptInterface
        fun cancelRealDownload(downloadId: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().destroyProcessById("jet_dl_$downloadId")
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun refreshLibrary() {
            pushScannedLibraryToWeb()
        }

        @JavascriptInterface
        fun playInNativeMedia3(title: String, filePath: String) {
            runOnUiThread {
                val file = File(filePath)
                if (!file.exists()) {
                    Toast.makeText(this@MainActivity, "File not found on storage", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                startActivity(Intent(this@MainActivity, VideoPlayerActivity::class.java).apply {
                    putExtra(VideoPlayerActivity.EXTRA_TITLE, title)
                    putExtra(VideoPlayerActivity.EXTRA_FILE_PATH, file.absolutePath)
                })
            }
        }

        @JavascriptInterface
        fun openFileExternal(filePath: String) {
            openFileInExternalApp(filePath)
        }

        @JavascriptInterface
        fun shareFile(filePath: String) {
            runOnUiThread {
                try {
                    val file = File(filePath)
                    if (!file.exists()) return@runOnUiThread
                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${applicationContext.packageName}.fileprovider",
                        file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "*/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share via JET"))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun updateYtDlpEngine() {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val status = synchronized(ytDlpEngineLock) {
                        ensureYtDlpInitialized()
                        YoutubeDL.getInstance().updateYoutubeDL(
                            applicationContext,
                            YoutubeDL.UpdateChannel.STABLE
                        )
                    }
                    val version = YoutubeDL.getInstance().version(applicationContext) ?: "Latest"
                    val result = JSONObject().apply {
                        put("ok", true)
                        put("status", status?.name ?: "DONE")
                        put("version", version)
                    }
                    callJs("onNativeEngineUpdated", result.toString())
                } catch (e: Exception) {
                    val result = JSONObject().apply {
                        put("ok", false)
                        put("error", e.message ?: "Engine update failed")
                    }
                    callJs("onNativeEngineUpdated", result.toString())
                }
            }
        }


        private val latestReleaseApiUrl =
            "https://api.github.com/repos/siraanabin/JET/releases/latest"

        private fun compareVersionStrings(remoteVersion: String, installedVersion: String): Int {
            fun parts(value: String): List<Int> = value.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
                .split('.')
                .map { it.toIntOrNull() ?: 0 }

            val remote = parts(remoteVersion)
            val installed = parts(installedVersion)
            for (index in 0 until maxOf(remote.size, installed.size)) {
                val left = remote.getOrElse(index) { 0 }
                val right = installed.getOrElse(index) { 0 }
                if (left != right) return left.compareTo(right)
            }
            return 0
        }

        @JavascriptInterface
        fun checkForAppUpdate(isManual: Boolean) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder()
                        .url(latestReleaseApiUrl)
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("Cache-Control", "no-cache")
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful || response.body == null) {
                            val error = JSONObject().apply {
                                put("ok", false)
                                put("manual", isManual)
                                put("error", "GitHub Releases unavailable (${response.code})")
                            }
                            callJs("onNativeAppUpdateResult", error.toString())
                            return@use
                        }

                        val release = JSONObject(response.body!!.string())
                        val tagVersion = release.optString("tag_name", "").trim()
                        val releaseNotes = release.optString("body", "").trim()
                        val assets = release.optJSONArray("assets")
                        var apkUrl = ""
                        if (assets != null) {
                            var fallbackApkUrl = ""
                            for (index in 0 until assets.length()) {
                                val asset = assets.optJSONObject(index) ?: continue
                                val name = asset.optString("name", "")
                                val url = asset.optString("browser_download_url", "")
                                if (!url.startsWith("https://") || !name.endsWith(".apk", ignoreCase = true)) continue
                                if (name.equals("app-release.apk", ignoreCase = true)) {
                                    apkUrl = url
                                    break
                                }
                                if (name.equals("JET-release.apk", ignoreCase = true) && fallbackApkUrl.isEmpty()) {
                                    fallbackApkUrl = url
                                } else if (fallbackApkUrl.isEmpty()) {
                                    fallbackApkUrl = url
                                }
                            }
                            if (apkUrl.isEmpty()) apkUrl = fallbackApkUrl
                        }

                        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                        } else {
                            @Suppress("DEPRECATION")
                            packageManager.getPackageInfo(packageName, 0)
                        }
                        val installedVersion = packageInfo.versionName ?: "0"
                        val versionIsNewer = tagVersion.isNotBlank() &&
                            compareVersionStrings(tagVersion, installedVersion) > 0
                        if (versionIsNewer && apkUrl.isBlank()) {
                            throw IllegalStateException("The latest GitHub Release has no downloadable APK asset.")
                        }
                        val hasUpdate = versionIsNewer

                        val result = JSONObject().apply {
                            put("ok", true)
                            put("manual", isManual)
                            put("hasUpdate", hasUpdate)
                            put("currentVersion", installedVersion)
                            put("latestVersion", tagVersion.removePrefix("v").removePrefix("V"))
                            put("tagName", tagVersion)
                            put("releaseName", release.optString("name", "JET Update"))
                            put("changelog", releaseNotes.ifBlank { "No release notes provided." })
                            put("apkUrl", apkUrl)
                            put("releaseUrl", release.optString("html_url", ""))
                        }
                        callJs("onNativeAppUpdateResult", result.toString())
                    }
                } catch (error: Exception) {
                    val result = JSONObject().apply {
                        put("ok", false)
                        put("manual", isManual)
                        put("error", error.message ?: "Update check failed")
                    }
                    callJs("onNativeAppUpdateResult", result.toString())
                }
            }
        }

        @JavascriptInterface
        fun downloadAndInstallAppUpdate(apkUrl: String) {
            val parsedUrl = Uri.parse(apkUrl)
            if (parsedUrl.scheme != "https" || parsedUrl.host != "github.com") {
                callJs("onNativeAppUpdateProgress", "-1, ${JSONObject.quote("Invalid GitHub APK URL")}")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Allow 'Install unknown apps' for JET, then tap Update again",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                callJs("onNativeAppUpdateProgress", "-1, ${JSONObject.quote("Install permission required")}")
                return
            }

            try {
                val downloadDirectory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: throw IllegalStateException("App download directory unavailable")
                val apkFile = File(downloadDirectory, "JET-update.apk")
                if (apkFile.exists()) apkFile.delete()

                val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                    setTitle("JET App Update")
                    setDescription("Downloading the latest signed JET APK")
                    setMimeType("application/vnd.android.package-archive")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(false)
                    setDestinationInExternalFilesDir(
                        this@MainActivity,
                        Environment.DIRECTORY_DOWNLOADS,
                        "JET-update.apk"
                    )
                }
                val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val downloadId = downloadManager.enqueue(request)
                getSharedPreferences("jet_app_update", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("download_id", downloadId)
                    .apply()
                callJs(
                    "onNativeAppUpdateProgress",
                    "0, ${JSONObject.quote("Download started in notification")}"
                )
            } catch (error: Exception) {
                callJs(
                    "onNativeAppUpdateProgress",
                    "-1, ${JSONObject.quote(error.message ?: "Unable to start APK download")}"
                )
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
