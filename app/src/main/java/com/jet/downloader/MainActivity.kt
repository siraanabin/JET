package com.jet.downloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import android.util.Base64
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
import com.jet.downloader.model.DownloadHistoryPayload
import com.jet.downloader.model.LibraryItemPayload
import com.jet.downloader.model.MediaInfoPayload
import com.jet.downloader.model.StreamSizePayload
import com.jet.downloader.model.OpenSourceLicenseItem
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.io.FileInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private val okHttpClient: OkHttpClient by inject()
    private val newPipeDownloader: JetOkHttpDownloader by inject()
    private val newPipeInitLock = Any()
    private val downloadDao: DownloadDao by inject()
    private val preferencesManager: PreferencesManager by inject()
    private val json: Json by inject()

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
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
                // Match the youtubedl-android 0.18.1 initialization contract:
                // initialize yt-dlp first, then extract FFmpeg's bundled support files.
                YoutubeDL.getInstance().init(applicationContext)
                JetFfmpegRuntime.initialize(applicationContext)

                // YoutubeDL 0.18.1 itself adds
                // --ffmpeg-location=<nativeLibraryDir>/libffmpeg.so to every execute().
                // Confirm that exact bundled executable is present and executable.
                val ffmpegExecutable = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")
                check(ffmpegExecutable.isFile) {
                    "Bundled FFmpeg executable is missing: ${ffmpegExecutable.absolutePath}"
                }
                if (!ffmpegExecutable.canExecute()) {
                    val permissionSet = ffmpegExecutable.setExecutable(true, false)
                    check(permissionSet && ffmpegExecutable.canExecute()) {
                        "Bundled FFmpeg executable is not executable and permissions could not be fixed: ${ffmpegExecutable.absolutePath}"
                    }
                }
                Log.i(
                    "JET-FFmpeg",
                    "FFmpeg executable verified: ${ffmpegExecutable.absolutePath}; executable=${ffmpegExecutable.canExecute()}; bytes=${ffmpegExecutable.length()}"
                )

                val ffmpegArchive = File(applicationInfo.nativeLibraryDir, "libffmpeg.zip.so")
                check(ffmpegArchive.isFile && ffmpegArchive.length() > 0L) {
                    "Bundled FFmpeg support archive is missing or empty: ${ffmpegArchive.absolutePath}"
                }
                val ffmpegSupportDir = File(noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
                check(ffmpegSupportDir.isDirectory && !ffmpegSupportDir.listFiles().isNullOrEmpty()) {
                    "Bundled FFmpeg support files were not extracted: ${ffmpegSupportDir.absolutePath}"
                }
                ytDlpReady = true
            }
        }
    }

    /**
     * Prefer mobile YouTube clients to avoid the stricter web-client PO-token path.
     * Keep TLS verification enabled and retain short network timeouts.
     */
    private fun applyYtDlpNetworkOptions(request: YoutubeDLRequest) {
        request.addOption("--force-ipv4")
        request.addOption("--socket-timeout", "10")
        request.addOption("--retries", "2")
        request.addOption("--fragment-retries", "2")
        request.addOption("--extractor-retries", "1")
        request.addOption("--file-access-retries", "1")
        request.addOption("--hls-prefer-native")
        request.addOption(
            "--extractor-args",
            "youtube:player_client=default,tv,web"
        )
    }

    private fun estimateBytesFromBitrateKbps(bitrateKbps: Double, durationSeconds: Long): Long {
        if (bitrateKbps <= 0.0 || durationSeconds <= 0L) return 0L
        return (bitrateKbps * 1000.0 / 8.0 * durationSeconds)
            .coerceAtMost(Long.MAX_VALUE.toDouble())
            .toLong()
            .coerceAtLeast(0L)
    }

    private fun fallbackVideoBitrateKbps(shortSide: Int): Double = when {
        shortSide <= 144 -> 100.0
        shortSide <= 240 -> 250.0
        shortSide <= 360 -> 700.0
        shortSide <= 480 -> 1_200.0
        shortSide <= 720 -> 2_500.0
        shortSide <= 1080 -> 3_500.0
        shortSide <= 1440 -> 6_000.0
        else -> 12_000.0
    }

    private fun isNewPipeFailureCached(url: String): Boolean {
        val failedAt = streamInfoFailureAt[url] ?: return false
        if (System.currentTimeMillis() - failedAt < 60_000L) return true
        streamInfoFailureAt.remove(url, failedAt)
        return false
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
        if (isNewPipeFailureCached(url)) {
            throw IllegalStateException("NewPipe extraction recently failed for this URL")
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
            streamInfoFailureAt[url] = System.currentTimeMillis()
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

    private val streamInfoFailureAt = ConcurrentHashMap<String, Long>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permissions handled */ }

    @SuppressLint("SetJavaScriptEnabled", "AllowFileAccessFromFileURLs")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Preserve the app's three-part layout; system bars remain outside WebView content.
        val initialThemeMode = runBlocking { preferencesManager.migrateLegacyThemePreference() }
        AppCompatDelegate.setDefaultNightMode(appCompatNightMode(initialThemeMode))
        applySystemBarTheme(isDarkTheme(initialThemeMode))
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
                    if (result != "true") {
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

    private fun appCompatNightMode(themeMode: String): Int = when (themeMode) {
        PreferencesManager.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        PreferencesManager.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private fun isSystemDarkMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun isDarkTheme(themeMode: String): Boolean = when (themeMode) {
        PreferencesManager.THEME_DARK -> true
        PreferencesManager.THEME_LIGHT -> false
        else -> isSystemDarkMode()
    }

    private fun applySystemBarTheme(isDarkMode: Boolean) {
        val window = window
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        // Match the system status bar to --bg-canvas and navigation bar to --bg-nav.
        // Keep system bars separate from app content to preserve the three-part layout.
        window.statusBarColor = Color.parseColor(if (isDarkMode) "#090C14" else "#F4F6FB")
        window.navigationBarColor = Color.parseColor(if (isDarkMode) "#0D121F" else "#FFFFFF")

        controller.isAppearanceLightStatusBars = !isDarkMode
        controller.isAppearanceLightNavigationBars = !isDarkMode
    }

    private fun syncDataStorePreferencesToWeb() {
        lifecycleScope.launch {
            val themeMode = preferencesManager.themeModeFlow.first()
            val clipEnabled = preferencesManager.clipboardDetectFlow.first()
            callJs(
                "onNativePreferencesLoaded",
                "${JSONObject.quote(themeMode)}, ${isSystemDarkMode()}, $clipEnabled"
            )
        }
    }

    private fun initializeFallbackEngine() {
        // Initialization is asynchronous. Do not run a binary update here:
        // the updater shares yt-dlp's process lock and could stall a newly queued download.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ensureYtDlpInitialized()
                withContext(Dispatchers.Main) {
                    if (::webView.isInitialized) notifyEngineState()
                }
            } catch (error: Exception) {
                Log.e("JET", "yt-dlp initialization failed; download fallback will retry on demand", error)
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
        val tab = intent.getStringExtra(DownloadNotificationService.EXTRA_OPEN_TAB).orEmpty()
        if (tab.isNotBlank() && ::webView.isInitialized) {
            webView.post {
                webView.evaluateJavascript(
                    "if (window.onNativeNotificationOpen) window.onNativeNotificationOpen(${JSONObject.quote(tab)});",
                    null
                )
            }
        }
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

    private fun createSmartMediaThumbnailDataUrl(file: File, isAudio: Boolean, savedThumbnailUrl: String = ""): String {
        if (savedThumbnailUrl.startsWith("data:image/", true) ||
            savedThumbnailUrl.startsWith("https://", true) ||
            savedThumbnailUrl.startsWith("http://", true)
        ) return savedThumbnailUrl

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val embedded = retriever.embeddedPicture
            val cover = embedded?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            val bitmap = cover ?: if (isAudio) null else {
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val durationUs = durationMs * 1_000L
                val firstTimeUs = if (durationUs > 4_000_000L) durationUs / 4 else 1_000_000L
                val firstFrame = retriever.getFrameAtTime(firstTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (firstFrame != null && isFrameTooDark(firstFrame) && durationUs > 4_000_000L) {
                    retriever.getFrameAtTime(durationUs / 2, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: firstFrame
                } else firstFrame
            } ?: return ""
            val scale = minOf(1.0, 320.0 / bitmap.width, 180.0 / bitmap.height)
            val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val scaled = if (width != bitmap.width || height != bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, width, height, true).also { if (it !== bitmap) bitmap.recycle() }
            } else bitmap
            ByteArrayOutputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 72, output)
                scaled.recycle()
                "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            }
        } catch (error: Exception) {
            Log.w("JET-Library", "Unable to create media thumbnail for ${file.name}", error)
            ""
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun isFrameTooDark(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 10).coerceAtLeast(1)
        val stepY = (bitmap.height / 10).coerceAtLeast(1)
        var luminance = 0.0
        var samples = 0
        for (x in 0 until bitmap.width step stepX) {
            for (y in 0 until bitmap.height step stepY) {
                val pixel = bitmap.getPixel(x, y)
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                luminance += 0.299 * red + 0.587 * green + 0.114 * blue
                samples++
            }
        }
        return samples > 0 && luminance / samples < 26.0
    }

    private fun pushScannedLibraryToWeb() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = getJetDownloadDir()
                val files = dir.listFiles()
                    ?.filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()

                val savedDownloads = downloadDao.getAll()
                val savedByPath = savedDownloads.associateBy { it.filePath }
                val history = savedDownloads.filter { it.filePath.isNotBlank() && File(it.filePath).isFile }.map { item ->
                    DownloadHistoryPayload(item.id, item.title, item.formatLabel, item.fileSize, item.filePath, Uri.fromFile(File(item.filePath)).toString(), item.thumbnailUrl, "Saved")
                }
                callJs("onNativeCompletedDownloads", json.encodeToString(history))
                val items = files.map { f ->
                    val lower = f.name.lowercase(Locale.US)
                    val type = when {
                        listOf(".mp3", ".m4a", ".opus", ".wav", ".aac", ".flac", ".ogg", ".oga", ".wma").any { lower.endsWith(it) } -> "audio"
                        lower.endsWith(".pdf") || lower.endsWith(".txt") || lower.endsWith(".zip") -> "document"
                        else -> "video"
                    }
                    val ext = f.extension.uppercase(Locale.US).ifEmpty { "FILE" }
                    val duration = if (type == "document") "" else runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(f.absolutePath)
                            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull() ?: 0L
                            formatDuration((durationMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        } finally {
                            retriever.release()
                        }
                    }.getOrDefault("")
                    LibraryItemPayload(
                        id = f.name.hashCode(),
                        title = f.nameWithoutExtension,
                        type = type,
                        meta = "${formatBytes(f.length())} · $ext",
                        duration = duration,
                        filePath = f.absolutePath,
                        fileUri = Uri.fromFile(f).toString(),
                        thumbnailUrl = if (type == "video" || type == "audio") {
                            createSmartMediaThumbnailDataUrl(
                                f,
                                isAudio = type == "audio",
                                savedThumbnailUrl = savedByPath[f.absolutePath]?.thumbnailUrl.orEmpty()
                            )
                        } else "",
                        size = formatBytes(f.length()),
                        format = ext
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

    private fun requestNotificationPermissionForDownload() {
        runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
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
        fun setThemeMode(mode: String) {
            val selectedMode = mode.trim().lowercase(Locale.ROOT)
            if (selectedMode !in setOf(
                    PreferencesManager.THEME_SYSTEM,
                    PreferencesManager.THEME_DARK,
                    PreferencesManager.THEME_LIGHT
                )
            ) return

            lifecycleScope.launch {
                preferencesManager.setThemeMode(selectedMode)
                withContext(Dispatchers.Main) {
                    AppCompatDelegate.setDefaultNightMode(appCompatNightMode(selectedMode))
                    applySystemBarTheme(isDarkTheme(selectedMode))
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
                    // Estimate sizes from cached NewPipe stream metadata and local bitrates only.
                    val bestAudio = info.audioStreams
                        .filter { it.isUrl }
                        .maxByOrNull { it.averageBitrate }

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
                    // Estimate locally from duration and bitrate; never wait for HTTP probes.
                    val bestAudioBytes = estimateBytesFromBitrateKbps(
                        bestAudio?.averageBitrate?.takeIf { it > 0 }?.toDouble() ?: 128.0,
                        durationSec
                    )
                    streamSizes["aud-fast"] = StreamSizePayload(sizeText = sizeText(bestAudioBytes))

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

                        val actualShortSide = video?.let { shortSide(it.width, it.height) } ?: targetShortSide
                        val videoKbps = video?.bitrate?.takeIf { it > 0 }?.toDouble()
                            ?: fallbackVideoBitrateKbps(actualShortSide)
                        val videoBytes = estimateBytesFromBitrateKbps(videoKbps, durationSec)
                        val needsAudio = video == null || video.isVideoOnly || video.bitrate <= 0
                        val totalBytes = videoBytes + if (needsAudio) bestAudioBytes else 0L
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
                            applyYtDlpNetworkOptions(this)
                            // Let yt-dlp choose supported YouTube clients automatically.
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
                            // yt-dlp tbr/abr are in kbps. Estimate bytes from bitrate and duration.
                            if (bitrateKbps > 0.0 && durationSec > 0L) {
                                return (bitrateKbps * 1000.0 / 8.0 * durationSec).toLong()
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
                        }?.takeIf { it > 0L } ?: estimateBytesFromBitrateKbps(128.0, durationSec)

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
                            val videoCandidates = formats.filter { format ->
                                val streamUrl = format.url ?: return@filter false
                                format.vcodec != null && format.vcodec != "none" &&
                                    streamUrl.isNotBlank() &&
                                    streamUrl.startsWith("https://", ignoreCase = true) &&
                                    !streamUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true) &&
                                    !streamUrl.substringBefore('?').endsWith(".mpd", ignoreCase = true) &&
                                    shortSide(format.width, format.height) in 1..targetShortSide
                            }
                            // Prefer direct HTTPS MP4/WebM/DASH media formats that expose
                            // an exact or approximate byte count; never let an HLS manifest
                            // outrank a downloadable media stream just because its bitrate is high.
                            val videoFormat = videoCandidates.maxWithOrNull(
                                compareBy<com.yausername.youtubedl_android.mapper.VideoFormat>(
                                    { if (it.fileSize > 0L || it.fileSizeApproximate > 0L) 1 else 0 },
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
                            }?.takeIf { it > 0L } ?: estimateBytesFromBitrateKbps(
                                videoFormat?.tbr?.takeIf { it > 0 }?.toDouble()
                                    ?: fallbackVideoBitrateKbps(targetShortSide),
                                durationSec
                            )
                            val isVideoOnly = videoFormat == null ||
                                videoFormat.acodec == null ||
                                videoFormat.acodec == "none" ||
                                videoFormat.tbr <= 0
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
            includeSubtitles: Boolean,
            thumbnailUrl: String
        ) {
            requestNotificationPermissionForDownload()
            runCatching { DownloadNotificationService.start(this@MainActivity, downloadId, title, thumbnailUrl) }
            lifecycleScope.launch(Dispatchers.IO) {
                val outDir = getJetDownloadDir()

                try {
                    downloadDao.upsert(DownloadEntity(downloadId, title, url, if (isAudio) audioExt.uppercase(Locale.US) else "${maxHeight}p", "", "", if (isAudio) "audio" else "video", thumbnailUrl = thumbnailUrl))
                    var resolvedThumbnailUrl = thumbnailUrl
                    // Fast direct stream download via NewPipe Extractor + OkHttp when muxing isn't needed
                    var downloadedViaNewPipe = false
                    if (!isNewPipeFailureCached(url) &&
                        !includeSubtitles &&
                        isAudio && audioExt.equals("m4a", ignoreCase = true)
                    ) {
                        try {
                            callJs("onNativeDownloadProgress", "${JSONObject.quote(downloadId)}, 8, ${JSONObject.quote("NewPipe Extractor...")}")
                            val info = getOrFetchStreamInfo(url)
                            if (resolvedThumbnailUrl.isBlank()) resolvedThumbnailUrl = info.thumbnails.maxByOrNull { it.width }?.url.orEmpty()
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
                                                        runCatching { DownloadNotificationService.update(this@MainActivity, downloadId, title, pct, speedStr, resolvedThumbnailUrl) }
                                                        lastReportTime = now
                                                        lastBytes = bytesReadTotal
                                                    }
                                                }
                                            }
                                        }
                                        if (outFile.exists() && outFile.length() > 0L) {
                                            finishDownloadSuccess(downloadId, url, outFile, if (isAudio) "audio" else "video", resolvedThumbnailUrl)
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

                    // Show extraction as a separate phase; yt-dlp can spend several seconds
                    // resolving formats before it reports actual byte progress.
                    callJs("onNativeDownloadProgress", "${JSONObject.quote(downloadId)}, 0, ${JSONObject.quote("Preparing / extracting streams…")}")
                    runCatching { DownloadNotificationService.update(this@MainActivity, downloadId, title, 0, "Preparing / extracting streams…", resolvedThumbnailUrl) }

                    // Adaptive 1080p/2K/4K FFmpeg muxing or MP3 conversion via yt-dlp + FFmpeg
                    ensureYtDlpInitialized()

                    val beforeFiles = outDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()
                    val request = YoutubeDLRequest(url)
                    request.addOption("-o", "${outDir.absolutePath}/%(title).80B [%(id)s].%(ext)s")
                    request.addOption("--no-mtime")
                    request.addOption("--no-playlist")
                    request.addOption("--ignore-config")
                    applyYtDlpNetworkOptions(request)
                    // Thumbnail metadata is carried over from the media-info request.

                    if (isAudio) {
                        request.addOption("-x")
                        request.addOption("--audio-format", audioExt.lowercase(Locale.US))
                        request.addOption("--audio-quality", audioBitrate)
                    } else {
                        // Resolve the best separate video/audio streams at or below the
                        // selected resolution. youtubedl-android injects its bundled
                        // --ffmpeg-location automatically; do not override that path.
                        val formatSelector =
                            "bestvideo[height<=${maxHeight}]+bestaudio/best[height<=${maxHeight}]/best"
                        request.addOption("-f", formatSelector)
                        request.addOption("--merge-output-format", "mp4")
                        request.addOption("--remux-video", "mp4")
                        if (includeSubtitles) {
                            request.addOption("--write-auto-subs")
                            request.addOption("--sub-lang", "en.*")
                        }
                    }

                    val speedRegex = Regex("at\\s+([0-9.]+\\s*[KMG]i?B/s)")
                    var lastReportedProgress = 0
                    synchronized(ytDlpEngineLock) {
                        YoutubeDL.getInstance().execute(request, "jet_dl_$downloadId") { progress, etaInSeconds, line ->
                        val reported = if (progress >= 0f) progress.toInt().coerceIn(0, 99) else lastReportedProgress
                        lastReportedProgress = maxOf(lastReportedProgress, reported)
                        val pct = lastReportedProgress
                        val speedMatch = speedRegex.find(line)?.groupValues?.getOrNull(1)
                        val speedLabel = when {
                            speedMatch != null -> "$speedMatch · ETA ${etaInSeconds}s"
                            line.contains("Merging", ignoreCase = true) || line.contains("ExtractAudio", ignoreCase = true) -> "FFmpeg processing..."
                            else -> "Downloading..."
                        }
                            callJs("onNativeDownloadProgress", "${JSONObject.quote(downloadId)}, $pct, ${JSONObject.quote(speedLabel)}")
                            runCatching { DownloadNotificationService.update(this@MainActivity, downloadId, title, pct, speedLabel, resolvedThumbnailUrl) }
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
                        finishDownloadSuccess(downloadId, url, newFile, if (isAudio) "audio" else "video", resolvedThumbnailUrl)
                    } else {
                        val fail = DownloadErrorPayload(downloadId, "Output file not found after download")
                        downloadDao.deleteById(downloadId)
                        runCatching { DownloadNotificationService.failed(this@MainActivity, downloadId, title, fail.error) }
                        callJs("onNativeDownloadError", json.encodeToString(fail))
                    }
                } catch (e: Exception) {
                    Log.e(
                        "JET-Download",
                        "Download failed for id=$downloadId; selectedFormat=${if (isAudio) "audio" else "${maxHeight}p"}; FFmpeg/yt-dlp stderr follows",
                        e
                    )
                    notifyEngineUpdateRequired(e)
                    val fail = DownloadErrorPayload(downloadId, e.message ?: "Download failed")
                    runCatching { downloadDao.deleteById(downloadId) }
                    runCatching { DownloadNotificationService.failed(this@MainActivity, downloadId, title, fail.error) }
                    callJs("onNativeDownloadError", json.encodeToString(fail))
                }
            }
        }

        private suspend fun finishDownloadSuccess(
            downloadId: String,
            url: String,
            file: File,
            mediaType: String,
            thumbnailUrl: String
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
                    mediaType = mediaType,
                    thumbnailUrl = thumbnailUrl
                )
            )

            val payload = DownloadCompletePayload(
                id = downloadId,
                title = file.nameWithoutExtension,
                size = sizeStr,
                filePath = file.absolutePath,
                fileUri = Uri.fromFile(file).toString()
            )
            runCatching { DownloadNotificationService.completed(this@MainActivity, downloadId, file.nameWithoutExtension, sizeStr, thumbnailUrl) }
            callJs("onNativeDownloadComplete", json.encodeToString(payload))
            pushScannedLibraryToWeb()
        }

        @JavascriptInterface
        fun cancelRealDownload(downloadId: String) {
            runCatching { DownloadNotificationService.cancel(this@MainActivity, downloadId) }
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { YoutubeDL.getInstance().destroyProcessById("jet_dl_$downloadId") }
                runCatching { downloadDao.deleteById(downloadId) }
            }
        }

        @JavascriptInterface
        fun getNotificationOpenTab(): String = intent?.getStringExtra(DownloadNotificationService.EXTRA_OPEN_TAB).orEmpty()

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
        fun showLibraryMenu(filePath: String, anchorX: Int, anchorY: Int) {
            runOnUiThread {
                if (filePath.isBlank()) {
                    Toast.makeText(this@MainActivity, "File path unavailable", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val file = File(filePath)
                if (!file.isFile) {
                    Toast.makeText(this@MainActivity, "File not found", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                val density = resources.displayMetrics.density
                val dialog = BottomSheetDialog(this@MainActivity)
                val sheet = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((22 * density).toInt(), (18 * density).toInt(), (22 * density).toInt(), (24 * density).toInt())
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(25, 29, 39))
                        cornerRadii = floatArrayOf(24 * density, 24 * density, 24 * density, 24 * density, 0f, 0f, 0f, 0f)
                    }
                }
                TextView(this@MainActivity).apply {
                    text = file.name
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    setPadding(0, 0, 0, (12 * density).toInt())
                    sheet.addView(this)
                }
                fun addAction(label: String, glyph: String, action: () -> Unit) {
                    TextView(this@MainActivity).apply {
                        text = "$glyph    $label"
                        textSize = 15f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding((8 * density).toInt(), 0, 8, 0)
                        minHeight = (52 * density).toInt()
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { dialog.dismiss(); action() }
                        sheet.addView(this, LinearLayout.LayoutParams(-1, -2))
                    }
                }
                addAction("Open With", "↗") { openFileInExternalApp(filePath) }
                addAction("Rename", "✎") {
                    val input = EditText(this@MainActivity).apply {
                        setText(file.nameWithoutExtension)
                        setSelection(text.length)
                        setSingleLine(true)
                    }
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Rename file")
                        .setView(input)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Rename") { _, _ -> renameLibraryFile(filePath, input.text.toString()) }
                        .show()
                }
                addAction("Delete", "⌫") {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete file?")
                        .setMessage("This permanently deletes the file from Downloads/JET.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ -> deleteLibraryFile(filePath) }
                        .show()
                }
                dialog.setContentView(sheet)
                dialog.show()
            }
        }

        @JavascriptInterface
        fun renameLibraryFile(filePath: String, requestedName: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val source = File(filePath).canonicalFile
                    val allowedDir = getJetDownloadDir().canonicalFile
                    require(source.parentFile == allowedDir && source.isFile) { "File is outside the JET Library" }
                    val cleanName = requestedName.trim()
                        .replace(Regex("""[\\/:*?"<>|]"""), "_")
                        .trim('.')
                        .take(100)
                    require(cleanName.isNotBlank()) { "Enter a valid file name" }
                    val targetName = if (cleanName.contains('.')) cleanName else "$cleanName.${source.extension}"
                    val target = File(allowedDir, targetName).canonicalFile
                    require(target.parentFile == allowedDir) { "Invalid file name" }
                    require(!target.exists()) { "A file with that name already exists" }
                    check(source.renameTo(target)) { "Unable to rename file" }
                    downloadDao.getAll().firstOrNull { it.filePath == source.absolutePath }?.let { saved ->
                        downloadDao.upsert(saved.copy(filePath = target.absolutePath))
                    }
                    MediaScannerConnection.scanFile(applicationContext, arrayOf(target.absolutePath), null, null)
                    pushScannedLibraryToWeb()
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "File renamed", Toast.LENGTH_SHORT).show() }
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, error.message ?: "Rename failed", Toast.LENGTH_LONG).show() }
                }
            }
        }

        @JavascriptInterface
        fun deleteLibraryFile(filePath: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val file = File(filePath).canonicalFile
                    val allowedDir = getJetDownloadDir().canonicalFile
                    require(file.parentFile == allowedDir && file.isFile) { "File is outside the JET Library" }
                    check(file.delete()) { "Unable to delete file" }
                    downloadDao.getAll().firstOrNull { it.filePath == file.absolutePath }?.let { downloadDao.deleteById(it.id) }
                    pushScannedLibraryToWeb()
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "File deleted", Toast.LENGTH_SHORT).show() }
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, error.message ?: "Delete failed", Toast.LENGTH_LONG).show() }
                }
            }
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
                            YoutubeDL.UpdateChannel.NIGHTLY
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
                    // A failed update must not disable an already-installed, usable engine.
                    val installedVersion = runCatching {
                        YoutubeDL.getInstance().version(applicationContext)
                    }.getOrNull()
                    val engineStillUsable = ytDlpReady && !installedVersion.isNullOrBlank()
                    val result = JSONObject().apply {
                        put("ok", false)
                        put("recoverable", engineStillUsable)
                        if (engineStillUsable) put("version", installedVersion)
                        put(
                            "error",
                            if (engineStillUsable) {
                                "Engine update failed; keeping the installed engine. " + (e.message ?: "")
                            } else {
                                e.message ?: "Engine update failed"
                            }
                        )
                    }
                    callJs("onNativeEngineUpdated", result.toString())
                }
            }
        }


        private val latestReleaseApiUrl =
            "https://api.github.com/repos/siraanabin/JET/releases/latest"
        private val releasesApiUrl =
            "https://api.github.com/repos/siraanabin/JET/releases?per_page=100"

        /**
         * GitHub's unauthenticated Releases API can return HTTP 403 when its
         * rate limit is exhausted. For Debug builds, fall back to GitHub's
         * public Releases HTML and derive the standard release asset URL.
         */
        private fun fetchLatestDebugReleaseFromWeb(): JSONObject {
            val pageRequest = okhttp3.Request.Builder()
                .url("https://github.com/siraanabin/JET/releases")
                .header("User-Agent", "JET-Android-Updater")
                .header("Cache-Control", "no-cache")
                .build()
            val pageHtml = okHttpClient.newCall(pageRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub Debug releases page unavailable (${response.code})")
                }
                response.body?.string()
                    ?: throw IllegalStateException("GitHub Debug releases page was empty.")
            }

            val tags = Regex("""/siraanabin/JET/releases/tag/(debug-v[0-9A-Za-z._-]+)""")
                .findAll(pageHtml)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            val latestTag = tags.maxWithOrNull { left, right ->
                compareVersionStrings(left.removePrefix("debug-v"), right.removePrefix("debug-v"))
            } ?: throw IllegalStateException("No published JET Debug update was found.")

            val assetUrl = "https://github.com/siraanabin/JET/releases/download/$latestTag/JET-debug.apk"
            return JSONObject().apply {
                put("tag_name", latestTag)
                put("name", "JET Debug ${latestTag.removePrefix("debug-v")}")
                put("body", "Debug update")
                put("html_url", "https://github.com/siraanabin/JET/releases/tag/$latestTag")
                put("assets", org.json.JSONArray().put(JSONObject().apply {
                    put("name", "JET-debug.apk")
                    put("browser_download_url", assetUrl)
                }))
            }
        }

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
                    val isDebugApp = packageName.endsWith(".debug")
                    val apiUrl = if (isDebugApp) releasesApiUrl else latestReleaseApiUrl
                    val release = if (isDebugApp) {
                        val request = okhttp3.Request.Builder()
                            .url(apiUrl)
                            .header("Accept", "application/vnd.github+json")
                            .header("X-GitHub-Api-Version", "2022-11-28")
                            .header("Cache-Control", "no-cache")
                            .build()
                        try {
                            okHttpClient.newCall(request).execute().use { response ->
                                if (!response.isSuccessful || response.body == null) {
                                    throw IllegalStateException("GitHub Releases API unavailable (${response.code})")
                                }
                                val releases = org.json.JSONArray(response.body!!.string())
                                (0 until releases.length())
                                    .mapNotNull { releases.optJSONObject(it) }
                                    .filter { candidate ->
                                        candidate.optBoolean("prerelease", false) &&
                                            candidate.optString("tag_name", "").startsWith("debug-v")
                                    }
                                    .maxWithOrNull { left, right ->
                                        compareVersionStrings(
                                            left.optString("tag_name").removePrefix("debug-v"),
                                            right.optString("tag_name").removePrefix("debug-v")
                                        )
                                    } ?: throw IllegalStateException("No published JET Debug update is available yet.")
                            }
                        } catch (apiError: Exception) {
                            fetchLatestDebugReleaseFromWeb()
                        }
                    } else {
                        val request = okhttp3.Request.Builder()
                            .url(apiUrl)
                            .header("Accept", "application/vnd.github+json")
                            .header("X-GitHub-Api-Version", "2022-11-28")
                            .header("Cache-Control", "no-cache")
                            .build()
                        okHttpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful || response.body == null) {
                                throw IllegalStateException("GitHub Releases unavailable (${response.code})")
                            }
                            JSONObject(response.body!!.string())
                        }
                    }

                        val tagName = release.optString("tag_name", "").trim()
                        val tagVersion = if (isDebugApp) {
                            tagName.removePrefix("debug-v")
                        } else {
                            tagName.removePrefix("v").removePrefix("V")
                        }
                        val releaseNotes = release.optString("body", "").trim()
                        val assets = release.optJSONArray("assets")
                        val expectedAssetName = if (isDebugApp) "JET-debug.apk" else "app-release.apk"
                        var apkUrl = ""
                        if (assets != null) {
                            for (index in 0 until assets.length()) {
                                val asset = assets.optJSONObject(index) ?: continue
                                if (asset.optString("name", "").equals(expectedAssetName, ignoreCase = true)) {
                                    val url = asset.optString("browser_download_url", "")
                                    if (url.startsWith("https://github.com/")) {
                                        apkUrl = url
                                        break
                                    }
                                }
                            }
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
                            throw IllegalStateException("The matching ${if (isDebugApp) "JET Debug" else "JET Release"} APK asset is missing from its GitHub update.")
                        }

                        val appLabel = if (isDebugApp) "JET Debug" else "JET"
                        val result = JSONObject().apply {
                            put("ok", true)
                            put("manual", isManual)
                            put("hasUpdate", versionIsNewer)
                            put("currentVersion", installedVersion)
                            put("latestVersion", tagVersion)
                            put("tagName", tagName)
                            put("appName", appLabel)
                            put("releaseName", release.optString("name", "$appLabel Update"))
                            put("changelog", releaseNotes.ifBlank { "No release notes provided." })
                            put("apkUrl", apkUrl)
                            put("releaseUrl", release.optString("html_url", ""))
                        }
                        callJs("onNativeAppUpdateResult", result.toString())
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

            lifecycleScope.launch {
                val apkFile = File(cacheDir, "updates/JET-update.apk")
                try {
                    withContext(Dispatchers.IO) {
                        val updateDirectory = apkFile.parentFile
                            ?: throw IllegalStateException("App update directory unavailable")
                        if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
                            throw IllegalStateException("Unable to create app update directory")
                        }
                        if (apkFile.exists() && !apkFile.delete()) {
                            throw IllegalStateException("Unable to replace previous APK")
                        }

                        val request = okhttp3.Request.Builder()
                            .url(apkUrl)
                            .header("User-Agent", "JET-Android-Updater")
                            .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
                            .build()

                        okHttpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw IllegalStateException("APK download failed (HTTP ${response.code})")
                            }
                            if (response.request.url.scheme != "https") {
                                throw IllegalStateException("APK download redirected to an insecure URL")
                            }
                            val body = response.body
                                ?: throw IllegalStateException("APK download response was empty")
                            val totalBytes = body.contentLength()
                            var downloadedBytes = 0L
                            val buffer = ByteArray(64 * 1024)

                            body.byteStream().use { input ->
                                FileOutputStream(apkFile).use { output ->
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count == -1) break
                                        output.write(buffer, 0, count)
                                        downloadedBytes += count

                                        val percent = if (totalBytes > 0L) {
                                            ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
                                        } else {
                                            0
                                        }
                                        val progressText = if (totalBytes > 0L) {
                                            "Downloading APK · $percent% · ${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
                                        } else {
                                            "Downloading APK · ${formatBytes(downloadedBytes)}"
                                        }
                                        callJs(
                                            "onNativeAppUpdateProgress",
                                            "$percent, ${JSONObject.quote(progressText)}"
                                        )
                                    }
                                    output.flush()
                                }
                            }
                        }

                        if (!apkFile.isFile || apkFile.length() < 1024L) {
                            throw IllegalStateException("Downloaded APK is incomplete")
                        }
                        FileInputStream(apkFile).use { input ->
                            if (input.read() != 0x50 || input.read() != 0x4B) {
                                throw IllegalStateException("Downloaded file is not a valid APK archive")
                            }
                        }
                    }

                    callJs(
                        "onNativeAppUpdateProgress",
                        "100, ${JSONObject.quote("Download complete · 100% · Opening installer…")}"
                    )
                    val apkUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${applicationContext.packageName}.fileprovider",
                        apkFile
                    )
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(installIntent)
                } catch (error: Exception) {
                    withContext(Dispatchers.IO) {
                        if (apkFile.exists()) apkFile.delete()
                    }
                    callJs(
                        "onNativeAppUpdateProgress",
                        "-1, ${JSONObject.quote(error.message ?: "Unable to download APK update")}"
                    )
                }
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
