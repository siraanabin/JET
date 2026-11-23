package com.jet.downloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.ComponentName
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
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.provider.MediaStore
import android.os.StatFs
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.palette.graphics.Palette
import android.util.Log
import android.util.LruCache
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
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
import androidx.activity.result.PickVisualMediaRequest
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.json.JSONArray
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

private data class RealDownloadSpec(
    val id: String, val url: String, val title: String, val isAudio: Boolean,
    val audioBitrate: String, val audioExt: String, val maxHeight: Int,
    val includeSubtitles: Boolean, val thumbnailUrl: String
)

class MainActivity : AppCompatActivity() {

    private val okHttpClient: OkHttpClient by inject()
    private val newPipeDownloader: JetOkHttpDownloader by inject()
    private val newPipeInitLock = Any()
    private val downloadDao: DownloadDao by inject()
    private val preferencesManager: PreferencesManager by inject()
    private val json: Json by inject()
    private val realDownloadSpecs = ConcurrentHashMap<String, RealDownloadSpec>()
    @Volatile private var cachedLibraryItems: List<LibraryItemPayload>? = null
    private val libraryCacheFile by lazy { File(cacheDir, "jet-library-cache.json") }
    private val libraryThumbnailCacheDir by lazy { File(cacheDir, "jet-library-thumbnails").apply { mkdirs() } }
    private val mediaRefreshHandler = Handler(Looper.getMainLooper())
    private var mediaStoreObserver: android.database.ContentObserver? = null
    private val mediaRefreshRunnable = Runnable { if (hasVideoReadPermission() || hasAudioReadPermission()) pushScannedLibraryToWeb(force = true) }
    private val pauseRequestedDownloads = ConcurrentHashMap.newKeySet<String>()
    private val downloadAdmissionMutex = kotlinx.coroutines.sync.Mutex()
    private var activeDownloadCount = 0

    private suspend fun acquireDownloadSlot(maxTasks: Int) {
        while (true) {
            val admitted = downloadAdmissionMutex.withLock {
                if (activeDownloadCount < maxTasks.coerceIn(1, 5)) {
                    activeDownloadCount++
                    true
                } else false
            }
            if (admitted) return
            kotlinx.coroutines.delay(300L)
        }
    }

    private suspend fun releaseDownloadSlot() {
        downloadAdmissionMutex.withLock {
            activeDownloadCount = (activeDownloadCount - 1).coerceAtLeast(0)
        }
    }
    private val pausedDownloads = ConcurrentHashMap.newKeySet<String>()

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private var pendingSharedUrl: String? = null
    private var lastDeliveredClipboardUrl: String? = null
    private var musicController: MediaController? = null
    private var musicControllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var musicSourceLabel = "Library"
    private var lastMusicVolume = 1f
    private val musicProgressHandler = Handler(Looper.getMainLooper())
    private val musicProgressRunnable = object : Runnable {
        override fun run() {
            val c = musicController ?: return
            pushMusicProgressToWeb()
            if (c.isPlaying) musicProgressHandler.postDelayed(this, 500L)
        }
    }
    private val musicPlayerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            pushMusicStateToWeb()
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) pushMusicQueueToWeb()
            musicProgressHandler.removeCallbacks(musicProgressRunnable)
            pushMusicProgressToWeb()
            if (player.isPlaying) musicProgressHandler.postDelayed(musicProgressRunnable, 500L)
        }
    }

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

    // Notification and legacy startup permissions remain separate from Library media access.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (granted) pushScannedLibraryToWeb()
    }

    private val libraryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val hasVideo = hasVideoReadPermission()
        val hasAudio = hasAudioReadPermission()
        val state = when {
            hasVideo && hasAudio -> "granted"
            hasVideo || hasAudio -> "partial"
            else -> "denied"
        }
        callJs("onNativeLibraryPermissionState", JSONObject.quote(state))
        if (hasVideo || hasAudio) {
            pushScannedLibraryToWeb()
        } else {
            Toast.makeText(this, "Allow audio and video access to load your Library.", Toast.LENGTH_LONG).show()
        }
    }

    private val feedbackMediaPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        val items = org.json.JSONArray()
        uris.take(9).forEach { uri ->
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "attachment"
            items.put(JSONObject().put("uri", uri.toString()).put("name", name).put("mime", contentResolver.getType(uri) ?: "application/octet-stream"))
        }
        callJs("onNativeFeedbackMediaPicked", items.toString())
    }
    @SuppressLint("SetJavaScriptEnabled", "AllowFileAccessFromFileURLs")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Preserve the app's three-part layout; system bars remain outside WebView content.
        val initialThemeMode = runBlocking { preferencesManager.migrateLegacyThemePreference() }
        AppCompatDelegate.setDefaultNightMode(appCompatNightMode(initialThemeMode))
        applySystemBarTheme(isDarkTheme(initialThemeMode))
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor(if (isDarkTheme(initialThemeMode)) "#080B12" else "#F4F6FB")))
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
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e(
                        "JET-WebView",
                        "${consoleMessage.message()} -- ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                syncDataStorePreferencesToWeb()
                connectMusicController()
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
        val html = assets.open("index.html").bufferedReader().use { it.readText() }
        val bootstrap = "<script>(function(){var m=" + JSONObject.quote(initialThemeMode) + ";var d=m==='dark'||(m==='system'&&" + isSystemDarkMode().toString() + ");document.documentElement.classList.toggle('dark',d);document.documentElement.style.backgroundColor=d?'#080B12':'#F4F6FB';try{localStorage.setItem('jet-theme-mode',m)}catch(e){}})();</script>"
        webView.loadDataWithBaseURL("file:///android_asset/", html.replaceFirst("<head>", "<head>$bootstrap"), "text/html", "UTF-8", null)
        registerMediaStoreObserver()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
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

    private fun connectMusicController() {
        if (musicController != null || musicControllerFuture != null || isFinishing || isDestroyed) return
        val token = SessionToken(this, ComponentName(this, PlayerPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        musicControllerFuture = future
        future.addListener({
            try {
                val controller = future.get()
                if (isFinishing || isDestroyed) { MediaController.releaseFuture(future); return@addListener }
                musicController = controller
                controller.addListener(musicPlayerListener)
                pushMusicStateToWeb()
                pushMusicQueueToWeb()
                pushMusicProgressToWeb()
            } catch (error: Exception) {
                Log.e("JET-Music", "MediaController connection failed", error)
                callJs("onNativeMusicError", JSONObject.quote(error.message ?: "Music service connection failed"))
                musicControllerFuture = null
            }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun isAudioFile(file: File): Boolean = file.extension.lowercase(Locale.US) in setOf("mp3","m4a","aac","wav","flac","ogg","oga","opus","wma")
    private fun buildAudioQueue(selected: File, selectedTitle: String): List<Pair<String,String>> {
        val queue = LinkedHashMap<String,String>()
        if (selected.isFile && isAudioFile(selected)) queue[selected.absolutePath] = selectedTitle.ifBlank { selected.nameWithoutExtension }
        if (hasAudioReadPermission()) runCatching {
            val projection = arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DISPLAY_NAME)
            contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, MediaStore.Audio.Media.DATE_ADDED + " DESC")?.use { cursor ->
                val pi=cursor.getColumnIndex(MediaStore.Audio.Media.DATA);val ti=cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);val ni=cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                while(cursor.moveToNext()&&queue.size<500){if(pi<0)continue;val path=cursor.getString(pi).orEmpty();if(path.isBlank())continue;val file=File(path);if(!file.isFile||!isAudioFile(file))continue;val title=if(ti>=0)cursor.getString(ti).orEmpty() else "";val name=if(ni>=0)cursor.getString(ni).orEmpty() else "";queue.putIfAbsent(file.absolutePath,title.ifBlank{name.ifBlank{file.nameWithoutExtension}})}
            }
        }.onFailure{Log.w("JET-Music","MediaStore queue query failed",it)}
        getJetDownloadDir().listFiles()?.filter{it.isFile&&isAudioFile(it)}?.sortedByDescending{it.lastModified()}?.forEach{if(queue.size<500)queue.putIfAbsent(it.absolutePath,it.nameWithoutExtension)}
        return queue.entries.map{it.key to it.value}
    }
    private fun startMusicPlayback(path:String,title:String,source:String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val selected=File(path)
            if(!selected.isFile||!isAudioFile(selected)){withContext(Dispatchers.Main){callJs("onNativeMusicError",JSONObject.quote("Audio file not found or unsupported"))};return@launch}
            val rows=buildAudioQueue(selected,title)
            val payload=JSONArray().apply{rows.forEach{(p,t)->put(JSONObject().put("path",p).put("title",t))}}.toString()
            withContext(Dispatchers.Main) {
                musicSourceLabel=source.ifBlank{"Library"}
                val intent=Intent(this@MainActivity,PlayerPlaybackService::class.java).apply{action=PlayerPlaybackService.ACTION_SET_QUEUE;putExtra(PlayerPlaybackService.EXTRA_QUEUE_JSON,payload);putExtra(PlayerPlaybackService.EXTRA_SELECTED_PATH,selected.absolutePath);putExtra(PlayerPlaybackService.EXTRA_SOURCE,musicSourceLabel)}
                try{ContextCompat.startForegroundService(this@MainActivity,intent);connectMusicController()}
                catch(e:Exception){Log.e("JET-Music","Service start failed",e);callJs("onNativeMusicError",JSONObject.quote(e.message?:"Unable to start audio playback"))}
            }
        }
    }
    private fun pushMusicStateToWeb() {
        val c=musicController?:return;val item=c.currentMediaItem;val meta=item?.mediaMetadata
        val state=JSONObject().apply{put("isPlaying",c.isPlaying);put("title",meta?.title?.toString().orEmpty());put("subtitle",meta?.artist?.toString().orEmpty());put("filePath",item?.mediaId.orEmpty());put("source",musicSourceLabel);put("currentIndex",c.currentMediaItemIndex.coerceAtLeast(0));put("shuffleEnabled",c.shuffleModeEnabled);put("repeatMode",when(c.repeatMode){Player.REPEAT_MODE_ONE->"one";Player.REPEAT_MODE_ALL->"all";else->"off"});put("volume",c.volume.toDouble());put("durationMs",c.duration.coerceAtLeast(0L));put("positionMs",c.currentPosition.coerceAtLeast(0L));put("playbackState",c.playbackState)}
        callJs("onNativeMusicStateChanged",state.toString())
    }
    private fun pushMusicProgressToWeb(){val c=musicController?:return;callJs("onNativeMusicProgress","${c.currentPosition.coerceAtLeast(0L)}, ${c.duration.coerceAtLeast(0L)}")}
    private fun buildMusicQueueJson():String {
        val controller = musicController ?: return "[]"
        return JSONArray().apply {
            for (index in 0 until controller.mediaItemCount) {
                val item = controller.getMediaItemAt(index)
                put(JSONObject()
                    .put("path", item.mediaId)
                    .put("title", item.mediaMetadata.title?.toString().orEmpty())
                    .put("current", index == controller.currentMediaItemIndex))
            }
        }.toString()
    }
    private fun pushMusicQueueToWeb(){callJs("onNativeMusicQueueChanged",buildMusicQueueJson())}
    private fun applyMusicSleepTimer(minutes:Int){val i=Intent(this,PlayerPlaybackService::class.java).apply{action=PlayerPlaybackService.ACTION_SLEEP_TIMER;putExtra(PlayerPlaybackService.EXTRA_SLEEP_TIMER_MINUTES,minutes.coerceIn(0,240))};runCatching{ContextCompat.startForegroundService(this,i)}.onFailure{Log.w("JET-Music","Sleep timer update failed",it)}}
    override fun onDestroy() {
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        musicProgressHandler.removeCallbacksAndMessages(null)
        mediaStoreObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        mediaStoreObserver = null
        musicController?.removeListener(musicPlayerListener)
        musicController = null
        musicControllerFuture?.let { runCatching { MediaController.releaseFuture(it) } }
        musicControllerFuture = null
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
        window.statusBarColor = Color.parseColor(if (isDarkMode) "#080B12" else "#F4F6FB")
        window.navigationBarColor = Color.parseColor(if (isDarkMode) "#121826" else "#FFFFFF")

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
        connectMusicController()
        if (::webView.isInitialized) {
            webView.postDelayed({
                dispatchClipboardUrlToWebView()
                if (hasVideoReadPermission() || hasAudioReadPermission()) {
                    webView.evaluateJavascript("if (window.onNativeAppResumed) window.onNativeAppResumed();", null)
                }
            }, 350)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lifecycleScope.launch {
            val mode = preferencesManager.themeModeFlow.first()
            val dark = isDarkTheme(mode)
            applySystemBarTheme(dark)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor(if (dark) "#080B12" else "#F4F6FB")))
            if (::webView.isInitialized) {
                syncDataStorePreferencesToWeb()
                webView.evaluateJavascript("if (window.onNativeSystemThemeChanged) window.onNativeSystemThemeChanged($dark);", null)
            }
        }
    }

    private fun registerMediaStoreObserver() {
        if (mediaStoreObserver != null) return
        mediaStoreObserver = object : android.database.ContentObserver(mediaRefreshHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                mediaRefreshHandler.removeCallbacks(mediaRefreshRunnable)
                mediaRefreshHandler.postDelayed(mediaRefreshRunnable, 900L)
            }
        }
        runCatching {
            contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver!!)
            contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver!!)
        }.onFailure { Log.w("JET-Library", "MediaStore observer registration failed", it) }
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
            } ?: return if (isAudio) "file:///android_asset/images/music-placeholder.svg" else ""
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
            if (isAudio) "file:///android_asset/images/music-placeholder.svg" else ""
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


    private fun extractAudioDominantColor(file: File): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val bytes = retriever.embeddedPicture ?: return "#20242E"
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return "#20242E"
            val color = try { Palette.from(bitmap).generate().getDominantColor(Color.rgb(32, 36, 46)) }
                finally { bitmap.recycle() }
            String.format(Locale.US, "#%06X", color and 0xFFFFFF)
        } catch (_: Exception) { "#20242E" }
        finally { runCatching { retriever.release() } }
    }

    private fun pushStorageStats(audioBytes: Long, videoBytes: Long) {
        runCatching {
            val stats = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            val total = stats.blockCountLong * stats.blockSizeLong
            val free = stats.availableBlocksLong * stats.blockSizeLong
            val used = (total - free).coerceAtLeast(0L)
            val payload = JSONObject().apply {
                put("totalBytes", total)
                put("audioBytes", audioBytes.coerceAtLeast(0L))
                put("videoBytes", videoBytes.coerceAtLeast(0L))
                put("otherBytes", (used - audioBytes - videoBytes).coerceAtLeast(0L))
                put("freeBytes", free)
            }
            callJs("onNativeStorageStats", payload.toString())
        }
    }

    private fun queryMediaAggregateSize(collection: Uri): Long {
        return try {
            contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                var totalBytes = 0L
                if (sizeIndex >= 0) {
                    while (cursor.moveToNext()) {
                        totalBytes = (totalBytes + cursor.getLong(sizeIndex).coerceAtLeast(0L))
                            .coerceAtMost(Long.MAX_VALUE)
                    }
                }
                totalBytes
            } ?: 0L
        } catch (error: Exception) {
            Log.w("JET-Library", "MediaStore aggregate size query failed for $collection", error)
            0L
        }
    }

    private suspend fun pushCompletedDownloadHistoryToWeb(files: List<File>): Map<String, DownloadEntity> {
        val savedDownloads = downloadDao.getAll()
        val savedByPath = savedDownloads.associateBy { it.filePath }
        val historyByPath = LinkedHashMap<String, DownloadHistoryPayload>()

        savedDownloads
            .filter { it.filePath.isNotBlank() && File(it.filePath).isFile }
            .forEach { item ->
                val mediaFile = File(item.filePath)
                val isVideo = mediaFile.extension.lowercase(Locale.US) in setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v")
                val resolvedThumbnail = item.thumbnailUrl.takeIf { it.isNotBlank() } ?: if (isVideo) thumbnailFromDiskCache(mediaFile) else ""
                historyByPath[item.filePath] = DownloadHistoryPayload(
                    item.id, item.title, item.formatLabel, item.fileSize, item.filePath,
                    Uri.fromFile(mediaFile).toString(), resolvedThumbnail, "Saved"
                )
            }

        // Recover older downloads that exist in Downloads/JET but have no Room row.
        files.forEach { file ->
            if (!historyByPath.containsKey(file.absolutePath)) {
                val saved = savedByPath[file.absolutePath]
                val ext = file.extension.uppercase(Locale.US).ifEmpty { "FILE" }
                historyByPath[file.absolutePath] = DownloadHistoryPayload(
                    saved?.id ?: "file-${file.absolutePath.hashCode()}",
                    saved?.title ?: file.nameWithoutExtension,
                    saved?.formatLabel ?: ext,
                    saved?.fileSize ?: formatBytes(file.length()),
                    file.absolutePath,
                    Uri.fromFile(file).toString(),
                    saved?.thumbnailUrl?.takeIf { it.isNotBlank() }
                        ?: if (file.extension.lowercase(Locale.US) in setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v")) thumbnailFromDiskCache(file) else "",
                    "Saved"
                )
            }
        }

        callJs("onNativeCompletedDownloads", json.encodeToString(historyByPath.values.toList()))
        return savedByPath
    }

    private fun thumbnailFromDiskCache(file: File): String {
        val key = (file.absolutePath + "|" + file.length() + "|" + file.lastModified()).hashCode().toUInt().toString(16)
        val cached = File(libraryThumbnailCacheDir, "$key.txt")
        if (cached.isFile) return runCatching { cached.readText() }.getOrDefault("")
        val data = createSmartMediaThumbnailDataUrl(file, false)
        if (data.isNotBlank()) runCatching { cached.writeText(data) }
        return data
    }

    private fun deliverCachedLibrary(items: List<LibraryItemPayload>) {
        callJs("onNativeLibraryScanStarted", "")
        items.chunked(50).forEachIndexed { index, chunk ->
            callJs("onNativeLibraryChunk", "${json.encodeToString(chunk)}, ${index == (items.size - 1) / 50}")
        }
        if (items.isEmpty()) callJs("onNativeLibraryChunk", "[], true")
    }

    private fun pushScannedLibraryToWeb(force: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!force) {
                    val diskItems = cachedLibraryItems ?: runCatching {
                        if (libraryCacheFile.isFile) json.decodeFromString<List<LibraryItemPayload>>(libraryCacheFile.readText()) else null
                    }.getOrNull()
                    if (diskItems != null) {
                        cachedLibraryItems = diskItems
                        deliverCachedLibrary(diskItems)
                        val audioBytes = if (hasAudioReadPermission()) queryMediaAggregateSize(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) else 0L
                        val videoBytes = if (hasVideoReadPermission()) queryMediaAggregateSize(MediaStore.Video.Media.EXTERNAL_CONTENT_URI) else 0L
                        pushStorageStats(audioBytes, videoBytes)
                        return@launch
                    }
                }
                callJs("onNativeLibraryScanStarted", "")
                val videoPermission = hasVideoReadPermission()
                val audioPermission = hasAudioReadPermission()

                // Storage capacity is obtained from filesystem metadata; media totals
                // are calculated by the MediaStore provider, not by summing every row.
                val audioBytes = if (audioPermission) queryMediaAggregateSize(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) else 0L
                val videoBytes = if (videoPermission) queryMediaAggregateSize(MediaStore.Video.Media.EXTERNAL_CONTENT_URI) else 0L
                pushStorageStats(audioBytes, videoBytes)

                val chunk = ArrayList<LibraryItemPayload>(50)
                val allItems = ArrayList<LibraryItemPayload>()
                var sentCount = 0
                fun flushChunk(isFinal: Boolean = false) {
                    if (chunk.isNotEmpty() || isFinal) {
                        callJs(
                            "onNativeLibraryChunk",
                            "${json.encodeToString(chunk)}, $isFinal"
                        )
                        sentCount += chunk.size
                        chunk.clear()
                    }
                }

                fun addItem(item: LibraryItemPayload) {
                    allItems.add(item)
                    chunk.add(item)
                    if (chunk.size >= 50) flushChunk()
                }

                fun queryCollection(collection: Uri, type: String) {
                    val projection = arrayOf(
                        MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.TITLE, MediaStore.MediaColumns.DATA,
                        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DURATION,
                        MediaStore.MediaColumns.MIME_TYPE
                    )
                    contentResolver.query(
                        collection, projection, null, null,
                        MediaStore.MediaColumns.DATE_ADDED + " DESC"
                    )?.use { cursor ->
                        val idIndex = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                        val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        val titleIndex = cursor.getColumnIndex(MediaStore.MediaColumns.TITLE)
                        val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                        val durationIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
                        val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                        while (cursor.moveToNext()) {
                            val path = if (dataIndex >= 0) cursor.getString(dataIndex).orEmpty() else ""
                            if (path.isBlank()) continue
                            val file = File(path)
                            if (!file.isFile || file.name.startsWith(".")) continue
                            val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex).coerceAtLeast(0L) else 0L
                            val displayName = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                            val title = if (titleIndex >= 0) cursor.getString(titleIndex).orEmpty() else ""
                            val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty() else ""
                            val durationMs = if (durationIndex >= 0) cursor.getLong(durationIndex).coerceAtLeast(0L) else 0L
                            val ext = file.extension.uppercase(Locale.US)
                                .ifEmpty { mime.substringAfter('/', "MEDIA").uppercase(Locale.US) }
                            addItem(
                                LibraryItemPayload(
                                    id = if (idIndex >= 0) cursor.getLong(idIndex).toInt() else file.absolutePath.hashCode(),
                                    title = title.ifBlank { file.nameWithoutExtension.ifBlank { displayName } },
                                    type = type,
                                    meta = formatBytes(size) + " · " + ext,
                                    duration = formatDuration((durationMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                                    filePath = file.absolutePath,
                                    fileUri = Uri.fromFile(file).toString(),
                                    // Create compact thumbnails on the background MediaStore scan dispatcher.
                                    thumbnailUrl = if (type == "video") thumbnailFromDiskCache(file) else "",
                                    size = formatBytes(size),
                                    format = ext,
                                    dominantColor = "#20242E"
                                )
                            )
                        }
                    }
                }

                if (videoPermission) queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video")
                if (audioPermission) queryCollection(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio")

                val dir = getJetDownloadDir()
                val files = dir.listFiles()
                    ?.filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
                val savedByPath = pushCompletedDownloadHistoryToWeb(files)

                for (file in files) {
                    val lower = file.name.lowercase(Locale.US)
                    val type = when {
                        listOf(".mp3", ".m4a", ".opus", ".wav", ".aac", ".flac", ".ogg", ".oga", ".wma").any { lower.endsWith(it) } -> "audio"
                        listOf(".mp4", ".mkv", ".webm", ".mov", ".avi", ".3gp", ".m4v").any { lower.endsWith(it) } -> "video"
                        else -> "document"
                    }
                    val ext = file.extension.uppercase(Locale.US).ifEmpty { "FILE" }
                    val size = file.length().coerceAtLeast(0L)
                    addItem(
                        LibraryItemPayload(
                            id = file.name.hashCode(), title = file.nameWithoutExtension, type = type,
                            meta = formatBytes(size) + " · " + ext, duration = "",
                            filePath = file.absolutePath, fileUri = Uri.fromFile(file).toString(),
                            thumbnailUrl = savedByPath[file.absolutePath]?.thumbnailUrl
                                ?.takeIf { it.isNotBlank() }
                                ?: if (type == "video") thumbnailFromDiskCache(file) else if (type == "audio") "file:///android_asset/images/music-placeholder.svg" else "",
                            size = formatBytes(size), format = ext, dominantColor = "#20242E"
                        )
                    )
                }
                flushChunk(isFinal = true)
                cachedLibraryItems = allItems.toList()
                runCatching { libraryCacheFile.writeText(json.encodeToString(allItems)) }
                    .onFailure { Log.w("JET-Library", "Could not persist Library cache", it) }
                Log.i("JET-Library", "Media scan delivered $sentCount items in 50-item chunks")
            } catch (error: Exception) {
                Log.e("JET-Library", "Device media scan failed", error)
                callJs("onNativeLibraryScanFailed", JSONObject.quote(error.message ?: "Media scan failed"))
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

    private fun hasVideoReadPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioReadPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun hasMediaReadPermission(): Boolean = hasVideoReadPermission() && hasAudioReadPermission()

    private fun requestLibraryMediaPermissions() {
        runOnUiThread {
            val missing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                buildList {
                    if (!hasVideoReadPermission()) add(Manifest.permission.READ_MEDIA_VIDEO)
                    if (!hasAudioReadPermission()) add(Manifest.permission.READ_MEDIA_AUDIO)
                }
            } else if (!hasMediaReadPermission()) {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            } else emptyList()

            if (missing.isEmpty()) {
                callJs("onNativeLibraryPermissionState", JSONObject.quote("granted"))
                pushScannedLibraryToWeb()
                return@runOnUiThread
            }

            val permissionPrefs = getSharedPreferences("jet_library_permissions", Context.MODE_PRIVATE)
            val requestedBefore = permissionPrefs.getBoolean("media_permissions_requested", false)
            val canExplain = missing.any { shouldShowRequestPermissionRationale(it) }

            if (requestedBefore && !canExplain) {
                AlertDialog.Builder(this)
                    .setTitle("Media access needed")
                    .setMessage("Allow JET to access audio and video files so the Library can scan your device. Enable the missing permissions in App settings.")
                    .setPositiveButton("Open settings") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    }
                    .setNegativeButton("Not now") { _, _ ->
                        callJs("onNativeLibraryPermissionState", JSONObject.quote(if (hasVideoReadPermission() || hasAudioReadPermission()) "partial" else "denied"))
                        if (hasVideoReadPermission() || hasAudioReadPermission()) pushScannedLibraryToWeb()
                    }
                    .show()
                return@runOnUiThread
            }

            permissionPrefs.edit().putBoolean("media_permissions_requested", true).apply()
            callJs("onNativeLibraryPermissionState", JSONObject.quote("requesting"))
            libraryPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun isMobileDataConnected(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
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
        fun getDownloadSettingsJson(): String {
            val prefs = runBlocking { preferencesManager.getDownloadPreferences() }
            return JSONObject().apply {
                put("defaultQuality", prefs.defaultQuality)
                put("saveSubtitles", prefs.saveSubtitles)
                put("maxDownloadTasks", prefs.maxDownloadTasks)
                put("speedLimitKbps", prefs.speedLimitKbps)
                put("allowMobileData", prefs.allowMobileData)
                put("libraryGridColumns", prefs.libraryGridColumns)
                put("downloadPath", getJetDownloadDir().absolutePath)
            }.toString()
        }

        @JavascriptInterface
        fun setDownloadPreference(key: String, value: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { preferencesManager.setDownloadPreference(key, value) }
                    .onFailure { Log.w("JET-Settings", "Invalid preference: " + key, it) }
            }
        }

        @JavascriptInterface
        fun pickFeedbackMedia(maxItems: Int) {
            runOnUiThread { feedbackMediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
        }

        @JavascriptInterface
        fun submitFeedback(payload: String) {
            val helperUrl = BuildConfig.JET_FEEDBACK_HELPER_URL.trim()
            if (helperUrl.isBlank()) {
                callJs("onNativeFeedbackResult", "false,${JSONObject.quote("Feedback service is not configured yet.")}")
                return
            }
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val input = JSONObject(payload)
                    val details = input.optString("details").trim().take(1000)
                    if (details.isBlank()) throw IllegalArgumentException("Please enter feedback details.")
                    val attachments = org.json.JSONArray()
                    val selected = input.optJSONArray("attachments") ?: org.json.JSONArray()
                    for (i in 0 until minOf(selected.length(), 9)) {
                        val uri = Uri.parse(selected.getString(i))
                        val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
                            while (true) { val n = stream.read(buffer); if (n <= 0) break; total += n
                                if (total > 5 * 1024 * 1024) throw IllegalArgumentException("Each attachment must be 5 MB or smaller.")
                                out.write(buffer, 0, n) }
                            out.toByteArray()
                        } ?: continue
                        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "attachment-$i"
                        attachments.put(JSONObject().put("name", name).put("mime", contentResolver.getType(uri) ?: "application/octet-stream").put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)))
                    }
                    val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
                    val body = JSONObject().put("details", details).put("contact", input.optString("contact").take(160)).put("attachments", attachments)
                        .put("diagnostics", JSONObject().put("appVersion", version).put("androidVersion", Build.VERSION.RELEASE ?: "unknown").put("deviceModel", Build.MODEL ?: "unknown"))
                    val request = Request.Builder().url(helperUrl).post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                    okHttpClient.newCall(request).execute().use { if (!it.isSuccessful) throw IllegalStateException("Feedback service returned HTTP ${it.code}.") }
                    callJs("onNativeFeedbackResult", "true,${JSONObject.quote("")}")
                } catch (error: Exception) {
                    Log.e("JET-Feedback", "Feedback submission failed", error)
                    callJs("onNativeFeedbackResult", "false,${JSONObject.quote(error.message ?: "Could not send feedback.")}")
                }
            }
        }

        @JavascriptInterface
        fun shareApp() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Try JET — Android video and audio downloader.")
                }
                startActivity(Intent.createChooser(intent, "Share JET"))
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
            realDownloadSpecs[downloadId] = RealDownloadSpec(
                downloadId, url, title, isAudio, audioBitrate, audioExt, maxHeight, includeSubtitles, thumbnailUrl
            )
            pauseRequestedDownloads.remove(downloadId)
            pausedDownloads.remove(downloadId)
            requestNotificationPermissionForDownload()
            runCatching { DownloadNotificationService.start(this@MainActivity, downloadId, title, thumbnailUrl) }
            lifecycleScope.launch(Dispatchers.IO) {
                val outDir = getJetDownloadDir()
                val downloadPrefs = preferencesManager.getDownloadPreferences()
                acquireDownloadSlot(downloadPrefs.maxDownloadTasks)

                try {
                    if (!downloadPrefs.allowMobileData && isMobileDataConnected()) {
                        val blocked = DownloadErrorPayload(downloadId, "Mobile data downloads are disabled in Settings")
                        callJs("onNativeDownloadError", json.encodeToString(blocked))
                        runCatching { DownloadNotificationService.failed(this@MainActivity, downloadId, title, blocked.error) }
                        return@launch
                    }
                    downloadDao.upsert(DownloadEntity(downloadId, title, url, if (isAudio) audioExt.uppercase(Locale.US) else "${maxHeight}p", "", "", if (isAudio) "audio" else "video", thumbnailUrl = thumbnailUrl))
                    val resolvedThumbnailUrl = thumbnailUrl

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
                    request.addOption("--continue")
                    applyYtDlpNetworkOptions(request)
                    if (downloadPrefs.speedLimitKbps > 0) {
                        request.addOption("--limit-rate", downloadPrefs.speedLimitKbps.toString() + "K")
                    }
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
                        if (includeSubtitles || downloadPrefs.saveSubtitles) {
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
                    if (pauseRequestedDownloads.remove(downloadId)) {
                        pausedDownloads.add(downloadId)
                        runCatching {
                            DownloadNotificationService.update(this@MainActivity, downloadId, title, 0, "Paused", thumbnailUrl)
                        }
                        callJs("onNativeDownloadPaused", JSONObject.quote(downloadId))
                        return@launch
                    }
                    Log.e(
                        "JET-Download",
                        "Download failed for id=$downloadId; selectedFormat=${if (isAudio) "audio" else "${maxHeight}p"}; FFmpeg/yt-dlp stderr follows",
                        e
                    )
                    notifyEngineUpdateRequired(e)
                    val fail = DownloadErrorPayload(downloadId, e.message ?: "Download failed")
                    runCatching { DownloadNotificationService.failed(this@MainActivity, downloadId, title, fail.error) }
                    callJs("onNativeDownloadError", json.encodeToString(fail))
                } finally {
                    releaseDownloadSlot()
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
            realDownloadSpecs.remove(downloadId)
            pauseRequestedDownloads.remove(downloadId)
            pausedDownloads.remove(downloadId)
            runCatching { DownloadNotificationService.completed(this@MainActivity, downloadId, file.nameWithoutExtension, sizeStr, thumbnailUrl) }
            callJs("onNativeDownloadComplete", json.encodeToString(payload))
            pushScannedLibraryToWeb()
        }

        @JavascriptInterface
        fun pauseRealDownload(downloadId: String) {
            if (!realDownloadSpecs.containsKey(downloadId)) return
            pauseRequestedDownloads.add(downloadId)
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { YoutubeDL.getInstance().destroyProcessById("jet_dl_$downloadId") }
            }
        }

        @JavascriptInterface
        fun resumeRealDownload(downloadId: String) {
            val spec = realDownloadSpecs[downloadId] ?: return
            if (!pausedDownloads.remove(downloadId)) return
            startRealDownload(spec.id, spec.url, spec.title, spec.isAudio, spec.audioBitrate,
                spec.audioExt, spec.maxHeight, spec.includeSubtitles, spec.thumbnailUrl)
        }

        @JavascriptInterface
        fun retryRealDownload(downloadId: String) {
            val spec = realDownloadSpecs[downloadId] ?: return
            startRealDownload(spec.id, spec.url, spec.title, spec.isAudio, spec.audioBitrate,
                spec.audioExt, spec.maxHeight, spec.includeSubtitles, spec.thumbnailUrl)
        }

        @JavascriptInterface
        fun cancelRealDownload(downloadId: String) {
            realDownloadSpecs.remove(downloadId)
            pauseRequestedDownloads.remove(downloadId)
            pausedDownloads.remove(downloadId)
            runCatching { DownloadNotificationService.cancel(this@MainActivity, downloadId) }
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { YoutubeDL.getInstance().destroyProcessById("jet_dl_$downloadId") }
                runCatching { downloadDao.deleteById(downloadId) }
            }
        }

        @JavascriptInterface
        fun getNotificationOpenTab(): String = intent?.getStringExtra(DownloadNotificationService.EXTRA_OPEN_TAB).orEmpty()

        @JavascriptInterface
        fun refreshDownloadHistory() {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val files = getJetDownloadDir().listFiles()
                        ?.filter {
                            it.isFile && !it.name.startsWith(".") &&
                                !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
                        }
                        ?.sortedByDescending { it.lastModified() }
                        ?: emptyList()
                    pushCompletedDownloadHistoryToWeb(files)
                } catch (error: Exception) {
                    Log.e("JET-Downloads", "Unable to restore download history", error)
                }
            }
        }

        @JavascriptInterface
        fun refreshLibrary() {
            if (hasVideoReadPermission() || hasAudioReadPermission()) pushScannedLibraryToWeb()
            else requestLibraryMediaPermissions()
        }

        @JavascriptInterface
        fun forceRefreshLibrary() {
            if (hasVideoReadPermission() || hasAudioReadPermission()) pushScannedLibraryToWeb(force = true)
            else requestLibraryMediaPermissions()
        }

        @JavascriptInterface fun playAudio(filePath:String,title:String,source:String){startMusicPlayback(filePath,title,source)}
        @JavascriptInterface fun musicPlayPause(){runOnUiThread{musicController?.let{if(it.isPlaying)it.pause() else it.play()}}}
        @JavascriptInterface fun musicSeekTo(positionMs:Long){runOnUiThread{musicController?.seekTo(positionMs.coerceAtLeast(0L))}}
        @JavascriptInterface fun musicNext(){runOnUiThread{musicController?.let{if(it.hasNextMediaItem())it.seekToNextMediaItem()}}}
        @JavascriptInterface fun musicPrevious(){runOnUiThread{musicController?.let{if(it.hasPreviousMediaItem())it.seekToPreviousMediaItem() else it.seekTo(0L)}}}
        @JavascriptInterface fun musicSetShuffle(enabled:Boolean){runOnUiThread{musicController?.shuffleModeEnabled=enabled}}
        @JavascriptInterface fun musicSetRepeat(mode:String){runOnUiThread{musicController?.repeatMode=when(mode.lowercase(Locale.ROOT)){"one"->Player.REPEAT_MODE_ONE;"all"->Player.REPEAT_MODE_ALL;else->Player.REPEAT_MODE_OFF}}}
        @JavascriptInterface fun musicSetVolume(volume:Double){runOnUiThread{val v=volume.toFloat().coerceIn(0f,1f);if(v>0f)lastMusicVolume=v;musicController?.volume=v}}
        @JavascriptInterface fun musicGetQueue():String=runBlocking(Dispatchers.Main){buildMusicQueueJson()}
        @JavascriptInterface fun musicPlayIndex(index:Int){runOnUiThread{musicController?.let{if(index in 0 until it.mediaItemCount){it.seekToDefaultPosition(index);it.play()}}}}
        @JavascriptInterface fun musicSleepTimer(minutes:Int){applyMusicSleepTimer(minutes)}
        @JavascriptInterface fun musicMinimize()=Unit

        @JavascriptInterface
        fun playInNativeMedia3(title: String, filePath: String) {
            runOnUiThread {
                val file = File(filePath)
                if (!file.exists()) {
                    Toast.makeText(this@MainActivity, "File not found on storage", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (isAudioFile(file)) { startMusicPlayback(file.absolutePath, title, "Library"); return@runOnUiThread }
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
                    val thumbnailKey = (file.absolutePath + "|" + file.length() + "|" + file.lastModified()).hashCode().toUInt().toString(16)
                    check(file.delete()) { "Unable to delete file" }
                    downloadDao.getAll().firstOrNull { it.filePath == file.absolutePath }?.let { downloadDao.deleteById(it.id) }
                    val updatedItems = (cachedLibraryItems ?: runCatching { if (libraryCacheFile.isFile) json.decodeFromString<List<LibraryItemPayload>>(libraryCacheFile.readText()) else emptyList() }.getOrDefault(emptyList()))
                        .filterNot { it.filePath == file.absolutePath }
                    cachedLibraryItems = updatedItems
                    runCatching { libraryCacheFile.writeText(json.encodeToString(updatedItems)) }
                    runCatching { File(libraryThumbnailCacheDir, "$thumbnailKey.txt").delete() }
                    callJs("onNativeLibraryItemDeleted", JSONObject.quote(file.absolutePath))
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
                    throw IllegalStateException("JET Debug release channel unavailable (${response.code})")
                }
                response.body?.string()
                    ?: throw IllegalStateException("JET Debug release channel returned no release.")
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
                                    throw IllegalStateException("Official Release Channel unavailable (${response.code})")
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
                                throw IllegalStateException("Official Release Channel unavailable (${response.code})")
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
                            throw IllegalStateException("The matching ${if (isDebugApp) "JET Debug" else "JET Release"} APK asset is missing from the Official Release Channel.")
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
                            put("releaseName", "Official Release Channel")
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
                callJs("onNativeAppUpdateProgress", "-1, ${JSONObject.quote("Invalid JET update package URL")}")
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
