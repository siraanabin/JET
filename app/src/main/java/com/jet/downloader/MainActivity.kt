package com.jet.downloader

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import com.jet.downloader.network.MAX_RETRIES
import com.jet.downloader.network.DISK_BUFFER_BYTES
import com.jet.downloader.network.CHUNK_SIZE_BYTES
import com.jet.downloader.network.DEFAULT_MAX_CONNECTIONS
import android.net.wifi.WifiManager
import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ContentUris
import android.content.ContentValues
import android.content.IntentSender
import android.content.res.Configuration
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.ThumbnailUtils
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.SystemClock
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
import androidx.activity.result.IntentSenderRequest
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private data class RealDownloadSpec(
    val id: String, val url: String, val title: String, val isAudio: Boolean,
    val audioBitrate: String, val audioExt: String, val maxHeight: Int, val selectedItag: Int,
    val includeSubtitles: Boolean, val thumbnailUrl: String, val selectedResolution: String
)

class MainActivity : AppCompatActivity() {

    private val okHttpClient: OkHttpClient by inject()
    private val newPipeDownloader: JetOkHttpDownloader by inject()
    private val newPipeInitLock = Any()
    private val downloadDao: DownloadDao by inject()
    private val preferencesManager: PreferencesManager by inject()
    private val json: Json by inject()
    private val realDownloadSpecs = ConcurrentHashMap<String, RealDownloadSpec>()
    private data class PrefetchedStreamChunk(val bytes: ByteArray, val totalBytes: Long, val cachedAtMs: Long)
    private val prefetchedStreamChunks = object : LruCache<String, PrefetchedStreamChunk>(1024) {
        override fun sizeOf(key: String, value: PrefetchedStreamChunk): Int = ((value.bytes.size + 1023) / 1024).coerceAtLeast(1)
    }
    private val prefetchedStreamChunkTtlMs = 15L * 60L * 1000L
    private val firstStreamChunkBytes = 256 * 1024
    private val downloadRangeChunkBytes = 5L * 1024L * 1024L
    private val activeNewPipeProcesses = ConcurrentHashMap<String, Process>()
    @Volatile private var cachedLibraryItems: List<LibraryItemPayload>? = null
    private val libraryCacheFile by lazy { File(cacheDir, "jet-library-cache.json") }
    private val libraryThumbnailCacheDir by lazy { File(cacheDir, "jet-library-thumbnails").apply { mkdirs() } }
    private val mediaRefreshHandler = Handler(Looper.getMainLooper())
    private var mediaStoreObserver: android.database.ContentObserver? = null
    private val mediaRefreshRunnable = Runnable { if (hasVideoReadPermission() || hasAudioReadPermission()) pushScannedLibraryToWeb(force = true) }
    private data class PendingLibraryMutation(val action: String, val filePath: String, val newName: String? = null, val mediaUri: Uri? = null)
    @Volatile private var pendingLibraryMutation: PendingLibraryMutation? = null
    private val libraryMutationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val pending = pendingLibraryMutation ?: return@registerForActivityResult
        if (hasLibraryMutationReadPermission(pending.filePath)) continuePendingLibraryMutation()
        else { pendingLibraryMutation = null; Toast.makeText(this, "Media permission is required to modify this file.", Toast.LENGTH_LONG).show() }
    }
    private val libraryMutationConsentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val pending = pendingLibraryMutation ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) continuePendingLibraryMutation(skipConsentRequest = true)
        else { pendingLibraryMutation = null; Toast.makeText(this, "Permission was not granted. File unchanged.", Toast.LENGTH_SHORT).show() }
    }

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
    private val videoPlaybackStartedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runCatching { musicController?.pause(); musicController?.clearMediaItems() }
            if (::webView.isInitialized) callJs("onNativeVideoPlaybackStarted", "")
        }
    }
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var lastMusicVolumeIndex = 0
    private var musicVolumeReceiverRegistered = false
    private val musicVolumeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stream = intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_MUSIC)
                ?: AudioManager.STREAM_MUSIC
            if (stream == AudioManager.STREAM_MUSIC) syncMusicVolumeToWeb()
        }
    }
    private var musicControllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var musicSourceLabel = "Library"
    private var lastMusicVolume = 1f
    private val musicProgressHandler = Handler(Looper.getMainLooper())
    private val musicProgressRunnable = object : Runnable {
        override fun run() {
            val c = musicController ?: return
            pushMusicProgressToWeb()
            if (c.isPlaying) musicProgressHandler.postDelayed(this, 100L)
        }
    }
    private val musicPlayerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            pushMusicStateToWeb()
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) pushMusicQueueToWeb()
            musicProgressHandler.removeCallbacks(musicProgressRunnable)
            pushMusicProgressToWeb()
            if (player.isPlaying) musicProgressHandler.postDelayed(musicProgressRunnable, 100L)
        }
    }

    @Volatile
    private var ytDlpReady: Boolean = false
    // Serializes yt-dlp initialization, extraction, downloads and updates.
    private val ytDlpEngineLock = Any()
    private val engineUpdateInFlight = AtomicBoolean(false)
    private val engineUpdatePreferences by lazy {
        getSharedPreferences("jet_engine_update", Context.MODE_PRIVATE)
    }
    private val engineUpdateCooldownMs = 24L * 60L * 60L * 1000L
    private val engineUpdateTimestampKey = "last_engine_update_check_ms"

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
        return (bitrateKbps * 1000.0 / 8.0 * durationSeconds).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong().coerceAtLeast(0L)
    }

    // NewPipe VideoStream.bitrate is bits/second; never multiply it by 1000 again.
    private fun estimateBytesFromBitrateBps(bitrateBps: Double, durationSeconds: Long): Long {
        if (bitrateBps <= 0.0 || durationSeconds <= 0L) return 0L
        return (bitrateBps / 8.0 * durationSeconds).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong().coerceAtLeast(0L)
    }

    private fun resolveExactStreamContentLength(streamUrl: String?): Long {
        if (streamUrl.isNullOrBlank()) return 0L
        exactStreamSizeCache[streamUrl]?.takeIf { it > 0L }?.let { return it }
        val queryLength = runCatching {
            Uri.parse(streamUrl).getQueryParameter("clen")?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
        }.getOrDefault(0L)
        val resolvedLength = queryLength.takeIf { it > 0L }
            ?: runCatching { newPipeDownloader.contentLength(streamUrl) }.getOrDefault(0L)
        if (resolvedLength > 0L) exactStreamSizeCache.putIfAbsent(streamUrl, resolvedLength)
        return resolvedLength
    }

    private fun videoResolutionId(shortSide: Int): String = when {
        shortSide >= 2160 -> "vid-4k"
        shortSide >= 1440 -> "vid-2k"
        shortSide >= 1080 -> "vid-1080"
        shortSide >= 720 -> "vid-720"
        shortSide >= 480 -> "vid-480"
        shortSide >= 360 -> "vid-360"
        shortSide >= 240 -> "vid-240"
        shortSide > 0 -> "vid-144"
        else -> ""
    }

    private fun videoResolutionLabel(shortSide: Int): String = when {
        shortSide >= 2160 -> "4K (2160p)"
        shortSide >= 1440 -> "2K (1440p)"
        shortSide >= 1080 -> "1080p"
        shortSide >= 720 -> "720p"
        shortSide >= 480 -> "480p"
        shortSide >= 360 -> "360p"
        else -> "${shortSide}p"
    }

    private fun extractVideoResolution(file: File): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val shortSide = if (width > 0 && height > 0) minOf(width, height) else maxOf(width, height)
            if (shortSide > 0) videoResolutionLabel(shortSide) else ""
        } catch (error: Exception) {
            Log.w("JET-Download", "Could not read output video dimensions for ${file.name}", error)
            ""
        } finally {
            runCatching { retriever.release() }
        }
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

    // Fifteen-minute canonical media cache shared by Analyze/prefetch and native Download.
    private val streamInfoCache=LruCache<String,StreamInfo>(12)
    private val streamInfoCacheAt=ConcurrentHashMap<String,Long>()
    private val streamInfoInFlight=ConcurrentHashMap<String,CompletableDeferred<StreamInfo>>()
    private val mediaInfoPrefetchJobs=ConcurrentHashMap<String,Job>()
    private val streamInfoCacheTtlMs=15*60*1000L
    private val exactStreamSizeCache = ConcurrentHashMap<String, Long>()
    private val verifiedNewPipeSourceSizes = ConcurrentHashMap<String, Long>()

    private fun normalizeMediaInfoUrl(rawUrl:String):String {
        return try {
            val parsed=Uri.parse(rawUrl.trim())
            val scheme=parsed.scheme?.lowercase(Locale.ROOT)
            val host=parsed.host?.lowercase(Locale.ROOT)
            if((scheme!="http"&&scheme!="https")||host.isNullOrBlank()) ""
            else {
                val authority=parsed.encodedAuthority?.replace(parsed.host.orEmpty(),host,ignoreCase=true)
                val defaultPort=(scheme=="http"&&parsed.port==80)||(scheme=="https"&&parsed.port==443)
                val normalizedAuthority=if(defaultPort)authority?.removeSuffix(":${parsed.port}") else authority
                val normalized=parsed.buildUpon().scheme(scheme).encodedAuthority(normalizedAuthority).fragment(null).build().toString()
                val videoId=when {
                    host=="youtu.be" -> parsed.pathSegments.firstOrNull()
                    host=="youtube.com" || host.endsWith(".youtube.com") ||
                        host=="youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") -> {
                        parsed.getQueryParameter("v") ?: parsed.pathSegments.let { segments ->
                            if(segments.firstOrNull() in setOf("shorts","embed","live","v")) segments.getOrNull(1) else null
                        }
                    }
                    else -> null
                }?.takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) }
                if(videoId!=null) "youtube:$videoId" else "url:$normalized"
            }
        } catch(_:Exception) { "" }
    }

    private suspend fun getOrFetchStreamInfo(url:String):StreamInfo {
        val cacheKey=normalizeMediaInfoUrl(url)
        require(cacheKey.isNotBlank()){"Only valid HTTP/HTTPS URLs can be inspected"}
        val now=System.currentTimeMillis()
        synchronized(streamInfoCache){
            val cached=streamInfoCache.get(cacheKey)
            val cachedAt=streamInfoCacheAt[cacheKey]?:0L
            if(cached!=null&&now-cachedAt in 0..streamInfoCacheTtlMs)return cached
            if(cached!=null)streamInfoCache.remove(cacheKey)
            streamInfoCacheAt.remove(cacheKey)
        }
        val pending=CompletableDeferred<StreamInfo>()
        val existing=streamInfoInFlight.putIfAbsent(cacheKey,pending)
        if(existing!=null)return existing.await()
        try{
            val info=extractStreamInfoWithReloadRetry(url)
            currentCoroutineContext().ensureActive()
            synchronized(streamInfoCache){
                streamInfoCache.put(cacheKey,info)
                streamInfoCacheAt[cacheKey]=System.currentTimeMillis()
            }
            pending.complete(info)
            return info
        }catch(error:Exception){
            // Failed/unsupported URL results are never retained.
            pending.completeExceptionally(error)
            throw error
        }finally{
            streamInfoInFlight.remove(cacheKey,pending)
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
            synchronized(streamInfoCache) { streamInfoCache.remove(normalizeMediaInfoUrl(url)); streamInfoCacheAt.remove(normalizeMediaInfoUrl(url)) }
            StreamInfo.getInfo(url)
        }
    }


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
        ContextCompat.registerReceiver(this, videoPlaybackStartedReceiver, android.content.IntentFilter("com.jet.downloader.action.VIDEO_PLAYBACK_STARTED"), ContextCompat.RECEIVER_NOT_EXPORTED)

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
                notifyEngineState()
                pendingSharedUrl?.let { shared ->
                    callJs("onNativeSharedUrl", JSONObject.quote(shared))
                    pendingSharedUrl = null
                }
                dispatchClipboardUrlToWebView()
                pushScannedLibraryToWeb()
                syncNetworkStateToJs()
                syncMusicVolumeToWeb()
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
        scheduleSilentEngineAutoUpdate()
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
        if (!musicVolumeReceiverRegistered) {
            ContextCompat.registerReceiver(
                this, musicVolumeReceiver,
                android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            musicVolumeReceiverRegistered = true
        }
        syncMusicVolumeToWeb()
    }

    override fun onStop() {
        if (::networkMonitor.isInitialized) networkMonitor.stop()
        if (musicVolumeReceiverRegistered) {
            runCatching { unregisterReceiver(musicVolumeReceiver) }
            musicVolumeReceiverRegistered = false
        }
        super.onStop()
    }

    private fun currentMusicVolumePercent(): Int {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maximum)
        if (current > 0) lastMusicVolumeIndex = current
        return (current * 100f / maximum).toInt().coerceIn(0, 100)
    }

    private fun lastMusicVolumePercent(): Int {
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val previous = lastMusicVolumeIndex.coerceIn(0, maximum)
        return (previous * 100f / maximum).toInt().coerceIn(0, 100)
    }

    private fun syncMusicVolumeToWeb() {
        if (!::webView.isInitialized) return
        callJs("onNativeMusicVolumeChanged", currentMusicVolumePercent().toString() + ", " + lastMusicVolumePercent().toString())
    }

    private fun connectMusicController() {
        try {
            if (musicController != null || musicControllerFuture != null || isFinishing || isDestroyed) return
            val token = SessionToken(this, ComponentName(this, PlayerPlaybackService::class.java))
            val future = MediaController.Builder(this, token).buildAsync()
            musicControllerFuture = future
            future.addListener({
                try {
                    val controller = future.get()
                    if (isFinishing || isDestroyed) {
                        runCatching { MediaController.releaseFuture(future) }
                        if (musicControllerFuture === future) musicControllerFuture = null
                        return@addListener
                    }
                    musicController = controller
                    controller.addListener(musicPlayerListener)
                    pushMusicStateToWeb()
                    pushMusicQueueToWeb()
                    pushMusicProgressToWeb()
                } catch (t: Throwable) {
                    Log.e("JET-Music", "MediaController connection failed", t)
                    runCatching { musicController?.removeListener(musicPlayerListener) }
                    musicController = null
                    if (musicControllerFuture === future) musicControllerFuture = null
                    runCatching { MediaController.releaseFuture(future) }
                    runCatching {
                        if (::webView.isInitialized && !isFinishing && !isDestroyed) {
                            callJs("onNativeMusicError", JSONObject.quote(t.message ?: "Music service connection failed"))
                        }
                    }
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (t: Throwable) {
            Log.e("JET-Music", "MediaController setup failed", t)
            musicControllerFuture?.let { runCatching { MediaController.releaseFuture(it) } }
            musicControllerFuture = null
            runCatching {
                if (::webView.isInitialized && !isFinishing && !isDestroyed) {
                    callJs("onNativeMusicError", JSONObject.quote(t.message ?: "Music service connection failed"))
                }
            }
        }
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
    private var requestedMusicArtworkPath = ""
    private fun pushMusicStateToWeb() {
        val c=musicController?:return;val item=c.currentMediaItem;val meta=item?.mediaMetadata
        val state=JSONObject().apply{put("isPlaying",c.isPlaying);put("title",meta?.title?.toString().orEmpty());put("subtitle",meta?.artist?.toString().orEmpty());put("filePath",item?.mediaId.orEmpty());put("source",musicSourceLabel);put("currentIndex",c.currentMediaItemIndex.coerceAtLeast(0));put("shuffleEnabled",c.shuffleModeEnabled);put("repeatMode",when(c.repeatMode){Player.REPEAT_MODE_ONE->"one";Player.REPEAT_MODE_ALL->"all";else->"off"});put("volume",c.volume.toDouble());put("systemVolumePercent",currentMusicVolumePercent());put("lastSystemVolumePercent",lastMusicVolumePercent());put("durationMs",c.duration.coerceAtLeast(0L));put("positionMs",c.currentPosition.coerceAtLeast(0L));put("playbackState",c.playbackState)}
        callJs("onNativeMusicStateChanged",state.toString())
        val path = item?.mediaId.orEmpty()
        if (path.isNotBlank() && path != requestedMusicArtworkPath) {
            requestedMusicArtworkPath = path
            lifecycleScope.launch(Dispatchers.IO) {
                val artwork = audioThumbnailFromDiskCache(File(path))
                withContext(Dispatchers.Main) {
                    if (musicController?.currentMediaItem?.mediaId == path) {
                        callJs("onNativeMusicArtwork", JSONObject.quote(artwork))
                    }
                }
            }
        }
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
                    .put("artworkUri", musicArtworkMemoryCache.get(item.mediaId).orEmpty())
                    .put("current", index == controller.currentMediaItemIndex))
            }
        }.toString()
    }
    private fun pushMusicQueueToWeb(){callJs("onNativeMusicQueueChanged",buildMusicQueueJson())}
    private fun applyMusicSleepTimer(minutes:Int){val i=Intent(this,PlayerPlaybackService::class.java).apply{action=PlayerPlaybackService.ACTION_SLEEP_TIMER;putExtra(PlayerPlaybackService.EXTRA_SLEEP_TIMER_MINUTES,minutes.coerceIn(0,240))};runCatching{ContextCompat.startForegroundService(this,i)}.onFailure{Log.w("JET-Music","Sleep timer update failed",it)}}
    override fun onDestroy() {
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(videoPlaybackStartedReceiver) }
        if (musicVolumeReceiverRegistered) runCatching { unregisterReceiver(musicVolumeReceiver) }
        musicVolumeReceiverRegistered = false
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
        // Initialize the bundled engine without doing network work on the startup path.
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

    private fun scheduleSilentEngineAutoUpdate() {
        // Wait until the intro and initial WebView work have had time to finish.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                lifecycleScope.launch(Dispatchers.IO) {
                    runSilentEngineAutoUpdateIfDue()
                }
            }
        }, 4_000L)
    }

    private suspend fun runSilentEngineAutoUpdateIfDue() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastCheck = engineUpdatePreferences.getLong(engineUpdateTimestampKey, 0L)
        if (lastCheck > 0L && now - lastCheck < engineUpdateCooldownMs) return@withContext

        // If a manual or automatic update is already active, leave this attempt unclaimed.
        if (!engineUpdateInFlight.compareAndSet(false, true)) return@withContext
        try {
            val result = runEngineUpdateBlocking()
            if (result.optBoolean("ok", false)) {
                engineUpdatePreferences.edit().putLong(engineUpdateTimestampKey, System.currentTimeMillis()).commit()
            }
            // Intentionally discard the result: automatic updates never notify the WebView.
        } catch (_: Exception) {
            // Silent by design. The next automatic attempt is after the cooldown.
        } finally {
            engineUpdateInFlight.set(false)
        }
    }

    private fun runEngineUpdateBlocking(): JSONObject {
        return try {
            val result = synchronized(ytDlpEngineLock) {
                ensureYtDlpInitialized()
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    applicationContext,
                    YoutubeDL.UpdateChannel.NIGHTLY
                )
                val version = YoutubeDL.getInstance().version(applicationContext) ?: "Latest"
                (status?.name ?: "DONE") to version
            }
            JSONObject().apply {
                put("ok", true)
                put("status", result.first)
                put("version", result.second)
            }
        } catch (error: Exception) {
            val installedVersion = runCatching {
                YoutubeDL.getInstance().version(applicationContext)
            }.getOrNull()
            val engineStillUsable = ytDlpReady && !installedVersion.isNullOrBlank()
            JSONObject().apply {
                put("ok", false)
                put("recoverable", engineStillUsable)
                if (engineStillUsable) put("version", installedVersion)
                put(
                    "error",
                    if (engineStillUsable) {
                        "Engine update failed; keeping the installed engine. " + (error.message ?: "")
                    } else {
                        error.message ?: "Engine update failed"
                    }
                )
            }
        }
    }

    private suspend fun runManualEngineUpdate() = withContext(Dispatchers.IO) {
        // Manual requests queue behind an automatic/manual run instead of racing it.
        while (!engineUpdateInFlight.compareAndSet(false, true)) {
            kotlinx.coroutines.delay(100L)
        }
        try {
            engineUpdatePreferences.edit()
                .putLong(engineUpdateTimestampKey, System.currentTimeMillis())
                .commit()
            val result = runEngineUpdateBlocking()
            withContext(Dispatchers.Main) {
                callJs("onNativeEngineUpdated", result.toString())
            }
        } finally {
            engineUpdateInFlight.set(false)
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
        if (bytes < 0L) return "Auto"
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1_048_576L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1_073_741_824L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
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
            var bitmap = cover ?: if (isAudio) null else {
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val durationUs = durationMs * 1_000L
                val firstTimeUs = if (durationUs > 4_000_000L) durationUs / 4 else 1_000_000L
                val firstFrame = retriever.getFrameAtTime(firstTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (firstFrame != null && isFrameTooDark(firstFrame) && durationUs > 4_000_000L) {
                    retriever.getFrameAtTime(durationUs / 2, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: firstFrame
                } else firstFrame
            }
            if (bitmap == null && !isAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                bitmap = runCatching { ThumbnailUtils.createVideoThumbnail(file, android.util.Size(320, 180), null) }.getOrNull()
            }
            val frame = bitmap ?: return if (isAudio) "file:///android_asset/images/music-placeholder.svg" else ""
            val scale = minOf(1.0, 320.0 / frame.width, 180.0 / frame.height)
            val width = (frame.width * scale).toInt().coerceAtLeast(1)
            val height = (frame.height * scale).toInt().coerceAtLeast(1)
            val scaled = if (width != frame.width || height != frame.height) {
                Bitmap.createScaledBitmap(frame, width, height, true).also { if (it !== frame) frame.recycle() }
            } else frame
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
                val resolvedThumbnail = item.thumbnailUrl.takeIf { it.isNotBlank() } ?: if (isVideo) thumbnailFromDiskCache(mediaFile) else if (isAudioFile(mediaFile)) audioThumbnailFromDiskCache(mediaFile) else ""
                val resolvedResolution = item.resolution.ifBlank { if (isVideo) extractVideoResolution(mediaFile) else "" }
                historyByPath[item.filePath] = DownloadHistoryPayload(
                    item.id, item.title, item.formatLabel, item.fileSize, item.filePath,
                    Uri.fromFile(mediaFile).toString(), resolvedThumbnail, "Saved", resolvedResolution
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
                        ?: if (file.extension.lowercase(Locale.US) in setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v")) thumbnailFromDiskCache(file) else if (isAudioFile(file)) audioThumbnailFromDiskCache(file) else "",
                    "Saved",
                    saved?.resolution ?: if (file.extension.lowercase(Locale.US) in setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v")) extractVideoResolution(file) else ""
                )
            }
        }

        callJs("onNativeCompletedDownloads", json.encodeToString(historyByPath.values.toList()))
        return savedByPath
    }

    private val musicArtworkMemoryCache = object : LruCache<String, String>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: String): Int = value.length * 2
    }
    private fun audioThumbnailFromDiskCache(file: File): String {
        val key = (file.absolutePath + "|" + file.length() + "|" + file.lastModified()).hashCode().toUInt().toString(16)
        val cached = File(libraryThumbnailCacheDir, "audio-$key.txt")
        if (cached.isFile) return runCatching { cached.readText().also { musicArtworkMemoryCache.put(file.absolutePath, it) } }.getOrDefault("")
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val picture = retriever.embeddedPicture ?: return ""
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(picture, 0, picture.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(picture, 0, picture.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return ""
            val scaled = if (decoded.width > 320 || decoded.height > 320) {
                val ratio = minOf(320f / decoded.width, 320f / decoded.height)
                Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt().coerceAtLeast(1), (decoded.height * ratio).toInt().coerceAtLeast(1), true).also { if (it !== decoded) decoded.recycle() }
            } else decoded
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
            if (!scaled.isRecycled) scaled.recycle()
            val data = "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            runCatching { cached.writeText(data) }
            musicArtworkMemoryCache.put(file.absolutePath, data)
            data
        } catch (error: Exception) {
            Log.d("JET-Music", "No embedded artwork for " + file.name, error)
            ""
        } finally {
            runCatching { retriever.release() }
        }
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
                            val resolution = if (type == "video") extractVideoResolution(file) else ""
                            addItem(
                                LibraryItemPayload(
                                    id = if (idIndex >= 0) cursor.getLong(idIndex).toInt() else file.absolutePath.hashCode(),
                                    title = title.ifBlank { file.nameWithoutExtension.ifBlank { displayName } },
                                    type = type,
                                    meta = formatBytes(size) + (resolution.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") + " · " + ext,
                                    duration = formatDuration((durationMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                                    filePath = file.absolutePath,
                                    fileUri = Uri.fromFile(file).toString(),
                                    // Create compact thumbnails on the background MediaStore scan dispatcher.
                                    thumbnailUrl = if (type == "video") thumbnailFromDiskCache(file) else if (type == "audio") audioThumbnailFromDiskCache(file) else "",
                                    size = formatBytes(size),
                                    format = ext,
                                    dominantColor = "#20242E",
                                    resolution = resolution
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
                            meta = formatBytes(size) + (if (type == "video") (savedByPath[file.absolutePath]?.resolution?.ifBlank { null } ?: extractVideoResolution(file)).takeIf { it.isNotBlank() }?.let { " · $it" } ?: "" else "") + " · " + ext, duration = "",
                            filePath = file.absolutePath, fileUri = Uri.fromFile(file).toString(),
                            thumbnailUrl = savedByPath[file.absolutePath]?.thumbnailUrl
                                ?.takeIf { it.isNotBlank() }
                                ?: if (type == "video") thumbnailFromDiskCache(file) else if (type == "audio") audioThumbnailFromDiskCache(file).ifBlank { "file:///android_asset/images/music-placeholder.svg" } else "",
                            size = formatBytes(size), format = ext, dominantColor = "#20242E",
                            resolution = savedByPath[file.absolutePath]?.resolution?.ifBlank { null }
                                ?: if (type == "video") extractVideoResolution(file) else ""
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

    private fun currentCachedLibraryItems(): List<LibraryItemPayload> {
        return cachedLibraryItems ?: runCatching {
            if (libraryCacheFile.isFile) json.decodeFromString<List<LibraryItemPayload>>(libraryCacheFile.readText()) else emptyList()
        }.getOrDefault(emptyList())
    }

    private fun hasLibraryMutationReadPermission(filePath: String): Boolean {
        val ext = File(filePath).extension.lowercase(Locale.ROOT)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ext in setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v") -> hasVideoReadPermission()
                ext in setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "wma") -> hasAudioReadPermission()
                else -> hasVideoReadPermission() || hasAudioReadPermission()
            }
        } else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun beginLibraryMutation(action: String, filePath: String, requestedName: String? = null) {
        runOnUiThread {
            if (filePath.isBlank()) { Toast.makeText(this, "File path unavailable", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
            pendingLibraryMutation = PendingLibraryMutation(action, filePath, requestedName)
            val missing = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val ext = File(filePath).extension.lowercase(Locale.ROOT)
                if (ext in setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v")) {
                    if (!hasVideoReadPermission()) missing.add(Manifest.permission.READ_MEDIA_VIDEO)
                } else if (ext in setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "wma")) {
                    if (!hasAudioReadPermission()) missing.add(Manifest.permission.READ_MEDIA_AUDIO)
                } else {
                    if (!hasVideoReadPermission()) missing.add(Manifest.permission.READ_MEDIA_VIDEO)
                    if (!hasAudioReadPermission()) missing.add(Manifest.permission.READ_MEDIA_AUDIO)
                }
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (missing.isNotEmpty()) libraryMutationPermissionLauncher.launch(missing.distinct().toTypedArray())
            else continuePendingLibraryMutation()
        }
    }

    private fun continuePendingLibraryMutation(skipConsentRequest: Boolean = false) {
        val pending = pendingLibraryMutation ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try { performLibraryMutation(pending, skipConsentRequest) }
            catch (error: Exception) {
                pendingLibraryMutation = null
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, error.message ?: "File operation failed", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun findLibraryMediaUri(file: File): Uri? {
        val collections = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME)
        val path = file.canonicalPath
        for (collection in collections) {
            val exact = runCatching {
                contentResolver.query(collection, projection, MediaStore.MediaColumns.DATA + " = ?", arrayOf(path), null)?.use { cursor ->
                    if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))) else null
                }
            }.getOrNull()
            if (exact != null) return exact
            val byName = runCatching {
                contentResolver.query(collection, projection, MediaStore.MediaColumns.DISPLAY_NAME + " = ?", arrayOf(file.name), null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        val rowPath = if (index >= 0) cursor.getString(index) else null
                        if (rowPath != null && runCatching { File(rowPath).canonicalPath == path }.getOrDefault(false)) {
                            return@use ContentUris.withAppendedId(collection, cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)))
                        }
                    }
                    null
                }
            }.getOrNull()
            if (byName != null) return byName
        }
        return null
    }

    private suspend fun performLibraryMutation(pending: PendingLibraryMutation, skipConsentRequest: Boolean) {
        val source = File(pending.filePath).canonicalFile
        val allowedDir = getJetDownloadDir().canonicalFile
        require(source.parentFile == allowedDir && source.isFile) { "File is outside the JET Library" }
        val uri = pending.mediaUri ?: findLibraryMediaUri(source) ?: throw IllegalStateException("File is not indexed in MediaStore. Refresh Library and try again.")
        val newName = if (pending.action == "rename") {
            val clean = (pending.newName ?: "").trim().replace(Regex("""[\\\\/:*?"<>|]"""), "_").trim('.').take(100)
            require(clean.isNotBlank()) { "Enter a valid file name" }
            val name = if (clean.contains('.')) clean else clean + "." + source.extension
            val target = File(allowedDir, name).canonicalFile
            require(target.parentFile == allowedDir) { "Invalid file name" }
            require(!target.exists() || target.canonicalPath == source.canonicalPath) { "A file with that name already exists" }
            name
        } else null
        try {
            if (pending.action == "rename") {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
                check(contentResolver.update(uri, values, null, null) > 0) { "MediaStore could not rename this file" }
                val target = File(allowedDir, newName!!).canonicalFile
                downloadDao.getAll().firstOrNull { it.filePath == source.absolutePath }?.let { downloadDao.upsert(it.copy(filePath = target.absolutePath)) }
                MediaScannerConnection.scanFile(applicationContext, arrayOf(target.absolutePath), null, null)
                val items = currentCachedLibraryItems().map { if (it.filePath == source.absolutePath) it.copy(filePath = target.absolutePath, title = target.nameWithoutExtension) else it }
                cachedLibraryItems = items
                runCatching { libraryCacheFile.writeText(json.encodeToString(items)) }
                callJs("onNativeLibraryItemRenamed", listOf(source.absolutePath, target.absolutePath, target.name).joinToString(",") { JSONObject.quote(it) })
                pendingLibraryMutation = null
                pushScannedLibraryToWeb(force = true)
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "File renamed", Toast.LENGTH_SHORT).show() }
            } else {
                val thumbnailKey = (source.absolutePath + "|" + source.length() + "|" + source.lastModified()).hashCode().toUInt().toString(16)
                check(contentResolver.delete(uri, null, null) > 0) { "MediaStore could not delete this file" }
                downloadDao.getAll().firstOrNull { it.filePath == source.absolutePath }?.let { downloadDao.deleteById(it.id) }
                val items = currentCachedLibraryItems().filterNot { it.filePath == source.absolutePath }
                cachedLibraryItems = items
                runCatching { libraryCacheFile.writeText(json.encodeToString(items)) }
                runCatching { File(libraryThumbnailCacheDir, "$thumbnailKey.txt").delete() }
                callJs("onNativeLibraryItemDeleted", JSONObject.quote(source.absolutePath))
                pendingLibraryMutation = null
                pushScannedLibraryToWeb(force = true)
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "File deleted", Toast.LENGTH_SHORT).show() }
            }
        } catch (error: android.app.RecoverableSecurityException) {
            if (skipConsentRequest) throw error
            withContext(Dispatchers.Main) { launchLibraryConsent(error.userAction.actionIntent.intentSender, pending.copy(mediaUri = uri)) }
        } catch (error: SecurityException) {
            if (skipConsentRequest || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw error
            withContext(Dispatchers.Main) {
                val consent = if (pending.action == "rename") MediaStore.createWriteRequest(contentResolver, listOf(uri))
                else MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                pendingLibraryMutation = pending.copy(mediaUri = uri)
                libraryMutationConsentLauncher.launch(IntentSenderRequest.Builder(consent.intentSender).build())
            }
        }
    }

    private fun launchLibraryConsent(sender: IntentSender, pending: PendingLibraryMutation) {
        pendingLibraryMutation = pending
        libraryMutationConsentLauncher.launch(IntentSenderRequest.Builder(sender).build())
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

        private fun prewarmGoogleVideoUrls(videoId: String, streams: List<Pair<Int, String>>) {
            streams.distinctBy { it.first }.forEach { (itag, streamUrl) ->
                if (itag <= 0 || streamUrl.isBlank()) return@forEach
                val host = runCatching { Uri.parse(streamUrl).host?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
                if (host != "googlevideo.com" && !host.endsWith(".googlevideo.com")) return@forEach
                val cacheKey = videoId + ":" + itag
                lifecycleScope.launch(Dispatchers.IO) {
                    if (prefetchedStreamChunks.get(cacheKey)?.let { System.currentTimeMillis() - it.cachedAtMs in 0..prefetchedStreamChunkTtlMs } == true) return@launch
                    val request = Request.Builder().url(streamUrl).get()
                        .header("Range", "bytes=0-262143")
                        .header("Accept-Encoding", "identity")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
                        .header("Origin", "https://www.youtube.com")
                        .header("Referer", "https://www.youtube.com/")
                        .build()
                    runCatching {
                        okHttpClient.newCall(request).execute().use { response ->
                            if (response.code != 206) return@use
                            val range = response.header("Content-Range") ?: return@use
                            val match = Regex("bytes 0-(\\d+)/(\\d+)").find(range) ?: return@use
                            val total = match.groupValues[2].toLongOrNull() ?: return@use
                            val bytes = response.body?.bytes() ?: return@use
                            if (bytes.isNotEmpty() && bytes.size <= firstStreamChunkBytes && bytes.size.toLong() <= total) {
                                prefetchedStreamChunks.put(cacheKey, PrefetchedStreamChunk(bytes, total, System.currentTimeMillis()))
                            }
                        }
                    }.onFailure { Log.d("JET-Prewarm", "Googlevideo prebuffer skipped: " + (it.message ?: "network error")) }
                }
            }
        }
        private fun buildNewPipeMediaInfoPayload(info: StreamInfo): MediaInfoPayload {
            val bestThumb = info.thumbnails.maxByOrNull { it.width }?.url ?: ""
            val durationSec = info.duration.toLong()
            // Estimate sizes from cached NewPipe stream metadata and local bitrates only.
            val bestAudio = info.audioStreams
                .filter { it.isUrl }
                .maxByOrNull { it.averageBitrate }
    
            fun shortSide(width: Int, height: Int): Int =
                if (width > 0 && height > 0) minOf(width, height) else maxOf(width, height)

            val streamSizes = mutableMapOf<String, StreamSizePayload>()
            listOf("aud-70" to 70L, "aud-128" to 128L, "aud-160" to 160L, "aud-320" to 320L)
                .forEach { (id, bitrateKbps) ->
                    val bytes = (bitrateKbps * 1000L * durationSec) / 8L
                    streamSizes[id] = StreamSizePayload(sizeText = formatBytes(bytes), sizeBytes = bytes, available = true)
                }

            val bestAudioBytes = resolveExactStreamContentLength(bestAudio?.url).takeIf { it > 0L }
                ?: estimateBytesFromBitrateKbps(bestAudio?.averageBitrate?.takeIf { it > 0 }?.toDouble() ?: 128.0, durationSec)
            streamSizes["aud-fast"] = StreamSizePayload(
                sizeText = formatBytes(bestAudioBytes), sizeBytes = bestAudioBytes, available = bestAudioBytes > 0L
            )

            // Only publish extracted streams. NewPipe VideoStream.bitrate is bits/second.
            val actualVideos = (info.videoOnlyStreams + info.videoStreams)
                .filter { it.isUrl && shortSide(it.width, it.height) > 0 }
            actualVideos.groupBy { videoResolutionId(shortSide(it.width, it.height)) }
                .filterKeys { it.isNotBlank() }
                .forEach { (id, candidates) ->
                    val video = candidates.maxWithOrNull(
                        compareBy<org.schabi.newpipe.extractor.stream.VideoStream>(
                            { shortSide(it.width, it.height) }, { it.bitrate }
                        )
                    ) ?: return@forEach
                    val short = shortSide(video.width, video.height)
                    val videoBytes = resolveExactStreamContentLength(video.url).takeIf { it > 0L }
                        ?: if (video.bitrate > 0) estimateBytesFromBitrateBps(video.bitrate.toDouble(), durationSec)
                        else estimateBytesFromBitrateKbps(fallbackVideoBitrateKbps(short), durationSec)
                    val totalBytes = videoBytes + if (video.isVideoOnly || video.bitrate <= 0) bestAudioBytes else 0L
                    streamSizes[id] = StreamSizePayload(
                        sizeText = formatBytes(totalBytes), sizeBytes = totalBytes, available = true,
                        itag = video.getItag().takeIf { it > 0 },
                        height = video.getHeight().takeIf { it > 0 },
                        shortSide = short, resolution = videoResolutionLabel(short)
                    )
                }

            val topVideo = (info.videoOnlyStreams + info.videoStreams)
                .filter { it.isUrl && it.getHeight() <= 1080 }
                .maxWithOrNull(compareBy<org.schabi.newpipe.extractor.stream.VideoStream>({ it.getHeight() }, { it.bitrate }))
                ?: (info.videoOnlyStreams + info.videoStreams).filter { it.isUrl }
                    .maxWithOrNull(compareBy<org.schabi.newpipe.extractor.stream.VideoStream>({ it.getHeight() }, { it.bitrate }))
            val topAudio = info.audioStreams.filter { it.isUrl }.maxByOrNull { it.averageBitrate }
            val prewarmStreams = listOfNotNull(
                topVideo?.url?.let { streamUrl -> topVideo.getItag() to streamUrl },
                topAudio?.url?.let { streamUrl -> topAudio.getItag() to streamUrl }
            )
            prewarmGoogleVideoUrls(info.id, prewarmStreams)

            val payload = MediaInfoPayload(
                ok = true,
                title = info.name ?: "JET Media Stream",
                duration = formatDuration(info.duration.toInt()),
                thumbnail = bestThumb,
                uploader = info.uploaderName ?: "",
                extractor = "NewPipe Extractor",
                streams = streamSizes
            )
    
            return payload
        }

        @JavascriptInterface
        fun prefetchMediaInfo(url: String) {
            val cacheKey=normalizeMediaInfoUrl(url)
            if(cacheKey.isBlank())return
            val job=lifecycleScope.launch(Dispatchers.IO,start=CoroutineStart.LAZY){
                try{
                    val info=getOrFetchStreamInfo(url)
                    val payload=buildNewPipeMediaInfoPayload(info)
                    callJs("onNativePrefetchedMediaInfo",JSONObject.quote(url)+","+JSONObject.quote(json.encodeToString(payload)))
                }catch(_:CancellationException){
                    // Clearing/editing the URL invalidates this silent request.
                }catch(_:Exception){
                    // Unsupported URLs/network errors are deliberately silent and uncached.
                    callJs("onNativePrefetchFailed",JSONObject.quote(url))
                }finally{
                    mediaInfoPrefetchJobs.remove(cacheKey,currentCoroutineContext()[Job])
                }
            }
            val existing=mediaInfoPrefetchJobs.putIfAbsent(cacheKey,job)
            if(existing==null)job.start() else job.cancel()
        }

        @JavascriptInterface
        fun cancelMediaInfoPrefetch(url: String) {
            val cacheKey=normalizeMediaInfoUrl(url)
            if(cacheKey.isBlank())return
            mediaInfoPrefetchJobs.remove(cacheKey)?.cancel()
        }

        @JavascriptInterface
        fun fetchMediaInfo(url: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // A prefetched StreamInfo is reused; concurrent requests share one extraction.
                    val info = getOrFetchStreamInfo(url)
                    val payload=buildNewPipeMediaInfoPayload(info)
                    callJs("onNativeMediaInfo", json.encodeToString(payload))
                } catch (npError: Exception) {
                    // 2. Fallback to yt-dlp for sites outside NewPipe Extractor
                    try {
                        ensureYtDlpInitialized()
                        val metadataRequest = YoutubeDLRequest(url).apply {
                            addOption("--dump-single-json")
                            addOption("--no-playlist")
                            addOption("--no-check-certificates")
                            addOption("--cache-dir", File(noBackupFilesDir, "yt-dlp-cache").apply { mkdirs() }.absolutePath)
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
                            // Ignore approximate container estimates; fall back to bitrate only when exact length is absent.
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
                        val bestAudioBytes = bestAudioFormat?.let { audioFormat ->
                            resolveExactStreamContentLength(audioFormat.url).takeIf { it > 0L } ?: formatBytes(
                                audioFormat.fileSize,
                                audioFormat.fileSizeApproximate,
                                (audioFormat.abr.takeIf { bitrate -> bitrate > 0 } ?: audioFormat.tbr).toDouble(),
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
                                sizeText = sizeLabel(bytes), sizeBytes = bytes, available = bytes > 0L
                            )
                        }
                        streamSizes["aud-fast"] = StreamSizePayload(
                            sizeText = sizeLabel(bestAudioBytes), sizeBytes = bestAudioBytes, available = bestAudioBytes > 0L
                        )

                        val videoCandidates = formats.filter { format ->
                            val streamUrl = format.url ?: return@filter false
                            format.vcodec != null && format.vcodec != "none" &&
                                streamUrl.isNotBlank() &&
                                streamUrl.startsWith("https://", ignoreCase = true) &&
                                !streamUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true) &&
                                !streamUrl.substringBefore('?').endsWith(".mpd", ignoreCase = true) &&
                                shortSide(format.width, format.height) > 0
                        }
                        // Publish only real extracted formats, grouped by short-side resolution.
                        videoCandidates.groupBy { videoResolutionId(shortSide(it.width, it.height)) }
                            .filterKeys { it.isNotBlank() }
                            .forEach { (id, candidates) ->
                                val videoFormat = candidates.maxWithOrNull(
                                    compareBy<com.yausername.youtubedl_android.mapper.VideoFormat>(
                                        { if (it.fileSize > 0L || it.fileSizeApproximate > 0L) 1 else 0 },
                                        { shortSide(it.width, it.height) }, { it.tbr }
                                    )
                                ) ?: return@forEach
                                val short = shortSide(videoFormat.width, videoFormat.height)
                                val videoBytes = resolveExactStreamContentLength(videoFormat.url).takeIf { it > 0L }
                                    ?: formatBytes(
                                        videoFormat.fileSize, videoFormat.fileSizeApproximate,
                                        videoFormat.tbr.toDouble(), durationSec
                                    ).takeIf { it > 0L } ?: estimateBytesFromBitrateKbps(
                                        videoFormat.tbr.takeIf { it > 0 }?.toDouble()
                                            ?: fallbackVideoBitrateKbps(short), durationSec
                                    )
                                val isVideoOnly = videoFormat.acodec == null || videoFormat.acodec == "none" || videoFormat.tbr <= 0
                                val totalBytes = if (isVideoOnly) videoBytes + bestAudioBytes else videoBytes
                                streamSizes[id] = StreamSizePayload(
                                    sizeText = sizeLabel(totalBytes), sizeBytes = totalBytes, available = true,
                                    height = videoFormat.height, shortSide = short,
                                    resolution = videoResolutionLabel(short)
                                )
                            }

                        // yt-dlp fallback format IDs are not NewPipe itags; only NewPipe streams use this cache.
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
            selectedItag: Int,
            includeSubtitles: Boolean,
            thumbnailUrl: String,
            selectedResolution: String
        ) {
            realDownloadSpecs[downloadId] = RealDownloadSpec(
                downloadId, url, title, isAudio, audioBitrate, audioExt, maxHeight, selectedItag, includeSubtitles, thumbnailUrl, selectedResolution
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
                    downloadDao.upsert(DownloadEntity(downloadId, title, url, if (isAudio) audioExt.uppercase(Locale.US) else "${maxHeight}p", "", "", if (isAudio) "audio" else "video", thumbnailUrl = thumbnailUrl, resolution = selectedResolution))
                    val resolvedThumbnailUrl = thumbnailUrl

                    // Show extraction as a separate phase; yt-dlp can spend several seconds
                    // resolving formats before it reports actual byte progress.
                    callJs("onNativeDownloadProgress", "${JSONObject.quote(downloadId)}, 0, ${JSONObject.quote("Preparing...")}, ${JSONObject.quote("PREPARING")}, 0, 0, 0, 0, -1")
                    runCatching { DownloadNotificationService.update(this@MainActivity, downloadId, title, 0, "Preparing...", resolvedThumbnailUrl) }

                    val newPipeFile = if (!isAudio) downloadWithNewPipe(downloadId, url, outDir, selectedItag, maxHeight) else null
                    if (newPipeFile != null) {
                        finishDownloadSuccess(downloadId, url, newPipeFile, "video", resolvedThumbnailUrl, selectedResolution)
                    } else {
                        if (pauseRequestedDownloads.remove(downloadId)) {
                            pausedDownloads.add(downloadId)
                            callJs("onNativeDownloadPaused", JSONObject.quote(downloadId))
                            return@launch
                        }
                        if (!realDownloadSpecs.containsKey(downloadId)) return@launch
                        // NewPipe stream selection or FFmpeg mux failed; use strict yt-dlp fallback.
                        ensureYtDlpInitialized()

                    val beforeFiles = outDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()
                    val request = YoutubeDLRequest(url)
                    request.addOption("-o", "${outDir.absolutePath}/%(title).80B [%(id)s].%(ext)s")
                    request.addOption("--no-mtime")
                    request.addOption("--no-playlist")
                    request.addOption("--no-check-certificates")
                    request.addOption("--cache-dir", File(noBackupFilesDir, "yt-dlp-cache").apply { mkdirs() }.absolutePath)
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
                        // Resolve only the selected itag or exact requested height.
                        // youtubedl-android injects its bundled
                        // --ffmpeg-location automatically; do not override that path.
                        val strictSelector = "bestvideo[height=${maxHeight}][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height=${maxHeight}]+bestaudio/best[height=${maxHeight}]"
                        // If the selected itag is unavailable, fail instead of silently changing quality.
                        val formatSelector = if (selectedItag > 0) "$selectedItag+bestaudio" else strictSelector
                        request.addOption("-f", formatSelector)
                        request.addOption("--merge-output-format", "mp4")
                        request.addOption("--remux-video", "mp4")
                        if (includeSubtitles || downloadPrefs.saveSubtitles) {
                            request.addOption("--write-auto-subs")
                            request.addOption("--sub-lang", "en.*")
                        }
                    }

                    val downloadedBytesRegex = Regex("""^.*?([0-9]+(?:\.[0-9]+)?)\s*(B|KiB|MiB|GiB)\b""")
                    val totalBytesRegex = Regex("""of\s+~?([0-9]+(?:\.[0-9]+)?)\s*(B|KiB|MiB|GiB)\b""")
                    var lastReportedProgress = 0
                    var lastBytesRead = 0L
                    var lastTotalBytes = 0L
                    fun parseYtDlpSize(match: MatchResult?): Long {
                        if (match == null) return 0L
                        val value = match.groupValues[1].toDoubleOrNull() ?: return 0L
                        val multiplier = when (match.groupValues[2]) { "KiB" -> 1024.0; "MiB" -> 1024.0 * 1024.0; "GiB" -> 1024.0 * 1024.0 * 1024.0; else -> 1.0 }
                        return (value * multiplier).toLong().coerceAtLeast(0L)
                    }
                    callJs("onNativeDownloadProgress", "${JSONObject.quote(downloadId)}, 0, ${JSONObject.quote("Connecting...")}, ${JSONObject.quote("CONNECTING")}, 0, 0, 0, 0, -1")
                    synchronized(ytDlpEngineLock) {
                        YoutubeDL.getInstance().execute(request, "jet_dl_$downloadId") { progress, _, line ->
                            val reported = if (progress >= 0f) progress.toInt().coerceIn(0, 99) else lastReportedProgress
                            lastReportedProgress = maxOf(lastReportedProgress, reported)
                            lastBytesRead = maxOf(lastBytesRead, parseYtDlpSize(downloadedBytesRegex.find(line)))
                            lastTotalBytes = maxOf(lastTotalBytes, parseYtDlpSize(totalBytesRegex.find(line)))
                            val merging = line.contains("Merging", ignoreCase = true) ||
                                line.contains("ExtractAudio", ignoreCase = true)
                            val stage = when {
                                merging -> "MERGING"
                                lastBytesRead > 0L -> "DOWNLOADING"
                                else -> "CONNECTING"
                            }
                            val pct = when {
                                stage == "MERGING" -> maxOf(lastReportedProgress, 99)
                                lastBytesRead > 0L -> maxOf(1, lastReportedProgress)
                                else -> 0
                            }.coerceAtMost(99)
                            val speedLabel = when {
                                merging -> "Merging..."
                                lastBytesRead == 0L -> "Connecting..."
                                else -> "Downloading..."
                            }
                            val callbackArgs = listOf(
                                JSONObject.quote(downloadId), pct.toString(),
                                JSONObject.quote(speedLabel), JSONObject.quote(stage),
                                lastBytesRead.toString(), lastBytesRead.toString(),
                                lastTotalBytes.toString(), "0", "-1",
                                JSONObject.quote("--"), JSONObject.quote("--"), "false", "false"
                            ).joinToString(", ")
                            callJs("onNativeDownloadProgress", callbackArgs)
                            runCatching {
                                DownloadNotificationService.update(
                                    this@MainActivity, downloadId, title, pct, speedLabel, resolvedThumbnailUrl
                                )
                            }
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
                        if (!isAudio && !containsVideoTrack(newFile)) {
                            newFile.delete()
                            throw IllegalStateException("Downloaded file has no video track; audio-only output was rejected.")
                        }
                        finishDownloadSuccess(downloadId, url, newFile, if (isAudio) "audio" else "video", resolvedThumbnailUrl, selectedResolution)
                    } else {
                        val fail = DownloadErrorPayload(downloadId, "Output file not found after download")
                        downloadDao.deleteById(downloadId)
                        runCatching { DownloadNotificationService.failed(this@MainActivity, downloadId, title, fail.error) }
                        callJs("onNativeDownloadError", json.encodeToString(fail))
                    }

                    }                } catch (e: Exception) {
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

        private fun downloadWithNewPipe(
            downloadId: String, url: String, outDir: File, selectedItag: Int, maxHeight: Int
        ): File? {
            var output = File(outDir, "jet-newpipe-" + downloadId + ".mp4")
            val videoPart = File(cacheDir, "jet-" + downloadId + "-video.part")
            val audioPart = File(cacheDir, "jet-" + downloadId + "-audio.part")
            var process: Process? = null
            var wifiLock: WifiManager.WifiLock? = null
            val downloadStartedAtMs = SystemClock.elapsedRealtime()
            try {
                wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "JET:MediaDownload").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                val info = runBlocking(Dispatchers.IO) { getOrFetchStreamInfo(url) }
                val candidates = (info.videoOnlyStreams + info.videoStreams).filter { it.isUrl }
                val selected = if (selectedItag > 0) {
                    candidates.firstOrNull { it.getItag() == selectedItag }
                } else {
                    candidates.firstOrNull { it.getHeight() == maxHeight }
                } ?: return null
                check(selected.getItag() > 0) { "Selected video stream has no valid itag" }
                val audio = if (selected.isVideoOnly) {
                    info.audioStreams.filter { it.isUrl }.maxByOrNull { it.averageBitrate } ?: return null
                } else null
                if (audio != null) check(audio.getItag() > 0) { "Selected audio stream has no valid itag" }
                val videoSuffix = selected.format.suffix.lowercase(Locale.ROOT)
                val audioSuffix = audio?.format?.suffix?.lowercase(Locale.ROOT).orEmpty()
                val useMkv = videoSuffix in setOf("webm", "mkv", "opus") ||
                    audioSuffix in setOf("webm", "opus", "mkv")
                output = File(outDir, "jet-newpipe-" + downloadId + if (useMkv) ".mkv" else ".mp4")
                if (output.exists()) output.delete()
                val checkpointFile = File(cacheDir, "jet-" + downloadId + ".chunks.ckpt")
                if (!checkpointFile.exists()) {
                    if (videoPart.exists()) videoPart.delete()
                    if (audioPart.exists()) audioPart.delete()
                }

                val selectedUrl = selected.url ?: return null
                val audioUrl = if (audio != null) audio.url ?: return null else null
                var videoTotalBytes = resolveExactStreamContentLength(selectedUrl)
                var audioTotalBytes = if (audio != null) resolveExactStreamContentLength(audioUrl) else 0L
                var totalBytes = Math.addExact(videoTotalBytes, audioTotalBytes)

                fun supportsRanges(streamUrl: String): Boolean {
                    return runCatching {
                        val probe = Request.Builder().url(streamUrl).get()
                            .header("Range", "bytes=0-0")
                            .header("Accept-Encoding", "identity")
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
                            .build()
                        JetOkHttpDownloader.withRangeRequestPermit {
                            okHttpClient.newCall(probe).execute().use { response ->
                                if (response.code != 206) false
                                else {
                                    val rangeTotal = response.header("Content-Range")
                                        ?.substringAfterLast("/")?.toLongOrNull() ?: 0L
                                    if (rangeTotal > 0L) {
                                        if (streamUrl == selectedUrl) videoTotalBytes = rangeTotal
                                        else audioTotalBytes = rangeTotal
                                        true
                                    } else false
                                }
                            }
                        }
                    }.getOrDefault(false)
                }
                val rangeSupported = videoTotalBytes > 0L && supportsRanges(selectedUrl) &&
                    (audio == null || (audioTotalBytes > 0L && supportsRanges(audioUrl!!)))
                if (rangeSupported) {
                    totalBytes = Math.addExact(videoTotalBytes, audioTotalBytes)
                }

                val totalDownloadedBytes = AtomicLong(0L)
                videoPart.parentFile?.mkdirs()
                val videoAccess = RandomAccessFile(videoPart, "rw")
                var audioAccess: RandomAccessFile? = null
                try {
                    // Preallocate only when the server has confirmed byte-range support.
                    if (rangeSupported) videoAccess.setLength(videoTotalBytes)
                    if (audio != null) {
                        audioAccess = RandomAccessFile(audioPart, "rw")
                        if (rangeSupported) audioAccess!!.setLength(audioTotalBytes)
                    }

                    val progressLock = Any()
                    var lastTickBytes = 0L
                    var lastTickTimeMs = SystemClock.elapsedRealtime()
                    var lastDataTimeMs = lastTickTimeMs
                    var smoothedSpeed = 0.0
                    var lastTransferPercent = 0

                    fun emitTransferProgress() {
                        synchronized(progressLock) {
                            val now = SystemClock.elapsedRealtime()
                            val currentBytes = totalDownloadedBytes.get().coerceIn(0L, totalBytes.coerceAtLeast(0L))
                            val deltaBytes = (currentBytes - lastTickBytes).coerceAtLeast(0L)
                            val deltaMs = (now - lastTickTimeMs).coerceAtLeast(1L)
                            if (deltaBytes > 0L) {
                                val bytesPerSec = (deltaBytes * 1000.0) / deltaMs.toDouble()
                                smoothedSpeed = if (smoothedSpeed <= 0.0) bytesPerSec
                                else 0.35 * bytesPerSec + 0.65 * smoothedSpeed
                                lastDataTimeMs = now
                            } else {
                                smoothedSpeed *= 0.5
                                if (now - lastDataTimeMs > 2000L) smoothedSpeed = 0.0
                            }
                            lastTickBytes = currentBytes
                            lastTickTimeMs = now

                            val remainingBytes = (totalBytes - currentBytes).coerceAtLeast(0L)
                            val etaSeconds = when {
                                currentBytes == totalBytes -> 0L
                                smoothedSpeed > 1024.0 ->
                                    kotlin.math.ceil(remainingBytes / smoothedSpeed).toLong()
                                else -> -1L
                            }
                            val speedText = if (smoothedSpeed >= 1024.0 * 1024.0) {
                                String.format(Locale.US, "%.1f MB/s", smoothedSpeed / (1024.0 * 1024.0))
                            } else {
                                String.format(Locale.US, "%.1f KB/s", smoothedSpeed / 1024.0)
                            }
                            val etaText = when {
                                etaSeconds < 0L -> "--"
                                etaSeconds < 60L -> etaSeconds.toString() + "s"
                                else -> (etaSeconds / 60L).toString() + "m " + (etaSeconds % 60L).toString() + "s"
                            }
                            val rawPercent = if (totalBytes > 0L) (currentBytes.toDouble() / totalBytes.toDouble() * 100.0).toInt().coerceIn(0, 100) else 0
                            lastTransferPercent = maxOf(lastTransferPercent, rawPercent)
                            val args = listOf(
                                JSONObject.quote(downloadId), lastTransferPercent.toString(),
                                JSONObject.quote("Downloading..."), JSONObject.quote("DOWNLOADING"),
                                currentBytes.toString(), currentBytes.toString(), totalBytes.toString(),
                                smoothedSpeed.toLong().coerceAtLeast(0L).toString(), etaSeconds.toString(),
                                JSONObject.quote(speedText), JSONObject.quote(etaText), "true", "false"
                            ).joinToString(", ")
                            callJs("onNativeDownloadProgress", args)
                        }
                    }

                    callJs(
                        "onNativeDownloadProgress",
                        listOf(
                            JSONObject.quote(downloadId), "0", JSONObject.quote("Downloading..."),
                            JSONObject.quote("DOWNLOADING"), "0", "0", totalBytes.toString(),
                            "0", "-1", JSONObject.quote("0.0 KB/s"), JSONObject.quote("--"),
                            "true", "false"
                        ).joinToString(", ")
                    )
                    val ticker = Executors.newSingleThreadScheduledExecutor { runnable ->
                        Thread(runnable, "JET-Download-Progress").apply { isDaemon = true }
                    }
                    try {
                        ticker.scheduleAtFixedRate(
                            { runCatching { emitTransferProgress() } }, 0L, 500L, TimeUnit.MILLISECONDS
                        )
                        if (rangeSupported) {
                            try {
                                runBlocking(Dispatchers.IO) {
                                    downloadSharedGoogleVideoParts(
                                        downloadId, info.id, selected.getItag(), selectedUrl,
                                        videoPart, videoTotalBytes, videoAccess.channel,
                                        audio?.getItag() ?: -1, audioUrl, audioPart, audioTotalBytes,
                                        audioAccess?.channel, totalDownloadedBytes
                                    )
                                }
                            } catch (rangeError: RangeUnsupportedException) {
                                File(cacheDir, "jet-" + downloadId + ".chunks.ckpt").delete()
                                totalDownloadedBytes.set(0L)
                                videoAccess.close()
                                audioAccess?.close()
                                videoTotalBytes = downloadSequentialStream(selectedUrl, videoPart, downloadId, totalDownloadedBytes)
                                audioTotalBytes = if (audioUrl != null) downloadSequentialStream(audioUrl, audioPart, downloadId, totalDownloadedBytes) else 0L
                                totalBytes = Math.addExact(videoTotalBytes, audioTotalBytes)
                            }
                        } else {
                            videoTotalBytes = downloadSequentialStream(selectedUrl, videoPart, downloadId, totalDownloadedBytes)
                            audioTotalBytes = if (audioUrl != null) downloadSequentialStream(audioUrl, audioPart, downloadId, totalDownloadedBytes) else 0L
                            totalBytes = Math.addExact(videoTotalBytes, audioTotalBytes)
                        }
                        emitTransferProgress()
                    } finally {
                        ticker.shutdownNow()
                    }
                } finally {
                    runCatching { audioAccess?.close() }
                    runCatching { videoAccess.close() }
                }

                check(videoPart.isFile && videoPart.length() == videoTotalBytes) {
                    "Downloaded video stream size does not match Content-Length"
                }
                if (audio != null) {
                    check(audioPart.isFile && audioPart.length() == audioTotalBytes) {
                        "Downloaded audio stream size does not match Content-Length"
                    }
                }
                check(totalDownloadedBytes.get() == totalBytes) {
                    "Combined downloaded bytes do not match the exact stream total"
                }

                callJs(
                    "onNativeDownloadProgress",
                    listOf(
                        JSONObject.quote(downloadId), "100", JSONObject.quote("Downloading..."),
                        JSONObject.quote("DOWNLOADING"), totalBytes.toString(), totalBytes.toString(),
                        totalBytes.toString(), "0", "0", JSONObject.quote("0.0 KB/s"),
                        JSONObject.quote("0s"), "true", "false"
                    ).joinToString(", ")
                )

                ensureYtDlpInitialized()
                val ffmpeg = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")
                check(ffmpeg.isFile && ffmpeg.canExecute()) { "Bundled FFmpeg is unavailable" }
                callJs(
                    "onNativeDownloadProgress",
                    listOf(
                        JSONObject.quote(downloadId), "0", JSONObject.quote("Merging audio & video..."),
                        JSONObject.quote("MERGING"), totalBytes.toString(), totalBytes.toString(),
                        totalBytes.toString(), "0", "-1", JSONObject.quote("--"),
                        JSONObject.quote("--"), "true", "false"
                    ).joinToString(", ")
                )
                val args = mutableListOf(ffmpeg.absolutePath, "-y", "-hide_banner", "-i", videoPart.absolutePath)
                if (audio != null) args += listOf("-i", audioPart.absolutePath, "-map", "0:v:0", "-map", "1:a:0")
                else args += listOf("-map", "0:v:0", "-map", "0:a?")
                args += listOf("-c", "copy")
                if (!useMkv) args += listOf("-movflags", "+faststart")
                args += listOf("-progress", "pipe:1", "-nostats", output.absolutePath)
                val runningProcess = ProcessBuilder(args).redirectErrorStream(true).start()
                process = runningProcess
                activeNewPipeProcesses[downloadId] = runningProcess
                val durationMicros = info.duration.coerceAtLeast(0L) * 1_000_000L
                var outTimeMicros = 0L
                var lastPercent = 0
                val muxStartedAtMs = SystemClock.elapsedRealtime()
                runningProcess.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (pauseRequestedDownloads.contains(downloadId) || !realDownloadSpecs.containsKey(downloadId)) {
                            runningProcess.destroyForcibly()
                            throw CancellationException("Download paused or cancelled")
                        }
                        if (line.startsWith("out_time_ms=")) {
                            outTimeMicros = maxOf(outTimeMicros, line.substringAfter("=").toLongOrNull() ?: 0L)
                        }
                        if (line.startsWith("progress=")) {
                            val finished = line.substringAfter("=") == "end"
                            val timedFallback = ((SystemClock.elapsedRealtime() - muxStartedAtMs) / 1500L).toInt().coerceIn(0, 99)
                            val pct = if (finished) 99 else if (durationMicros > 0L) {
                                (outTimeMicros.toDouble() * 100.0 / durationMicros.toDouble()).toInt().coerceIn(0, 99)
                            } else timedFallback
                            lastPercent = maxOf(lastPercent, pct).coerceAtMost(99)
                            callJs(
                                "onNativeDownloadProgress",
                                listOf(
                                    JSONObject.quote(downloadId), lastPercent.toString(),
                                    JSONObject.quote("Merging audio & video..."),
                                    JSONObject.quote("MERGING"), totalBytes.toString(), totalBytes.toString(),
                                    totalBytes.toString(), "0", "-1", JSONObject.quote("--"),
                                    JSONObject.quote("--"), "true", "false"
                                ).joinToString(", ")
                            )
                        }
                    }
                }
                val exitCode = runningProcess.waitFor()
                val mergeSec = ((SystemClock.elapsedRealtime() - muxStartedAtMs).coerceAtLeast(0L)) / 1000L
                activeNewPipeProcesses.remove(downloadId, runningProcess)
                if (exitCode == 0 && output.isFile && output.length() > 0L && containsVideoTrack(output)) {
                    verifiedNewPipeSourceSizes[downloadId] = totalBytes
                    val dlSec = ((muxStartedAtMs - downloadStartedAtMs).coerceAtLeast(1L)) / 1000.0
                    val videoMb = String.format(Locale.US, "%.1f", videoTotalBytes / (1024.0 * 1024.0))
                    val audioMb = String.format(Locale.US, "%.1f", audioTotalBytes / (1024.0 * 1024.0))
                    val avgMbps = String.format(Locale.US, "%.2f", totalDownloadedBytes.get() / (1024.0 * 1024.0) / dlSec)
                    Log.d("DL_DEBUG", "conn=8 chunk=${CHUNK_SIZE_BYTES / (1024 * 1024)}MB video=${videoMb}MB audio=${audioMb}MB download=${dlSec}s avg=${avgMbps} MB/s retries=0 blocks=0 merge=${mergeSec}s")
                    callJs(
                        "onNativeDownloadProgress",
                        listOf(
                            JSONObject.quote(downloadId), "100", JSONObject.quote("Complete"),
                            JSONObject.quote("MERGING"), totalBytes.toString(), totalBytes.toString(),
                            totalBytes.toString(), "0", "0", JSONObject.quote("0.0 KB/s"),
                            JSONObject.quote("0s"), "true", "true"
                        ).joinToString(", ")
                    )
                    return output
                }
                output.delete()
                return null
            } catch (error: Exception) {
                verifiedNewPipeSourceSizes.remove(downloadId)
                activeNewPipeProcesses.remove(downloadId)?.let { runCatching { it.destroyForcibly() } }
                output.delete()
                Log.w("JET-NewPipe", "Local parallel NewPipe download/remux failed; falling back to yt-dlp", error)
                return null
            } finally {
                activeNewPipeProcesses.remove(downloadId)
                runCatching { process?.destroy() }
                if (!File(cacheDir, "jet-" + downloadId + ".chunks.ckpt").exists()) {
                    runCatching { videoPart.delete() }
                    runCatching { audioPart.delete() }
                }
                runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
            }
        }

        private class RangeUnsupportedException(message: String) : IOException(message)

        private fun downloadSequentialStream(streamUrl: String, target: File, downloadId: String, downloaded: AtomicLong): Long {
            val request = Request.Builder().url(streamUrl).get()
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .build()
            return JetOkHttpDownloader.withRangeRequestPermit {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Sequential stream failed with HTTP " + response.code)
                    val input = response.body?.byteStream() ?: throw IOException("Sequential stream body is empty")
                    var countTotal = 0L
                    target.parentFile?.mkdirs()
                    input.use {
                        FileOutputStream(target, false).use { output ->
                            val buffer = ByteArray(DISK_BUFFER_BYTES)
                            while (true) {
                                if (pauseRequestedDownloads.contains(downloadId) || !realDownloadSpecs.containsKey(downloadId)) {
                                    throw CancellationException("Download paused or cancelled")
                                }
                                val count = it.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                output.write(buffer, 0, count)
                                countTotal += count.toLong()
                            }
                            output.fd.sync()
                        }
                    }
                    countTotal
                }
            }.also { downloaded.addAndGet(it) }
        }

        private fun downloadSharedGoogleVideoParts(
            downloadId: String, videoId: String, videoItag: Int, videoUrl: String,
            videoFile: File, videoSize: Long, videoChannel: FileChannel,
            audioItag: Int, audioUrl: String?, audioFile: File, audioSize: Long,
            audioChannel: FileChannel?, downloaded: AtomicLong
        ) {
            data class Task(val audio: Boolean, val start: Long, val end: Long, val index: Int)
            val queue = ConcurrentLinkedQueue<Task>()
            val checkpoint = File(cacheDir, "jet-" + downloadId + ".chunks.ckpt")
            val completed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val checkpointLock = Any()
            val retryCount = AtomicInteger(0)
            val blockCount = AtomicInteger(0)
            val activeLimit = AtomicInteger(DEFAULT_MAX_CONNECTIONS)
            val consecutiveFailures = AtomicInteger(0)
            val previous = runCatching { if (checkpoint.isFile) JSONObject(checkpoint.readText()) else null }.getOrNull()
            val validCheckpoint = previous != null &&
                previous.optLong("videoSize") == videoSize && previous.optLong("audioSize") == audioSize &&
                previous.optInt("videoItag") == videoItag && previous.optInt("audioItag") == audioItag
            if (validCheckpoint) {
                val entries = previous!!.optJSONArray("completed")
                if (entries != null) for (i in 0 until entries.length()) {
                    entries.optString(i).takeIf { it.isNotBlank() }?.let { completed.add(it) }
                }
            } else {
                checkpoint.delete()
                videoFile.setLength(0L)
                videoFile.setLength(videoSize)
                if (audioUrl != null) {
                    audioFile.setLength(0L)
                    audioFile.setLength(audioSize)
                }
            }
            fun saveCheckpoint() {
                synchronized(checkpointLock) {
                    checkpoint.writeText(JSONObject()
                        .put("videoSize", videoSize).put("audioSize", audioSize)
                        .put("videoItag", videoItag).put("audioItag", audioItag)
                        .put("completed", JSONArray().apply { completed.sorted().forEach { put(it) } })
                        .toString())
                }
            }
            fun seed(isAudio: Boolean, size: Long) {
                var start = 0L
                var index = 0
                while (start < size) {
                    val end = minOf(size - 1L, start + CHUNK_SIZE_BYTES - 1L)
                    val key = (if (isAudio) "a:" else "v:") + index
                    if (key !in completed) queue.add(Task(isAudio, start, end, index))
                    else downloaded.addAndGet(end - start + 1L)
                    start = end + 1L
                    index++
                }
            }
            seed(true, audioSize)
            seed(false, videoSize)
            fun poll(wantAudio: Boolean): Task? {
                for (task in queue) if (task.audio == wantAudio && queue.remove(task)) return task
                return null
            }
            fun stepDown() {
                while (true) {
                    val old = activeLimit.get()
                    val next = when (old) { 8 -> 6; 6 -> 4; else -> 4 }
                    if (old == next || activeLimit.compareAndSet(old, next)) return
                }
            }
            fun runTask(task: Task) {
                val targetUrl = if (task.audio) audioUrl!! else videoUrl
                val channel = if (task.audio) audioChannel!! else videoChannel
                val expectedSize = if (task.audio) audioSize else videoSize
                var offset = task.start
                var attempts = 0
                while (offset <= task.end) {
                    if (pauseRequestedDownloads.contains(downloadId) || !realDownloadSpecs.containsKey(downloadId)) {
                        throw CancellationException("Download paused or cancelled")
                    }
                    try {
                        JetOkHttpDownloader.withRangeRequestPermit {
                            val request = Request.Builder().url(targetUrl).get()
                                .header("Range", "bytes=" + offset + "-" + task.end)
                                .header("Accept-Encoding", "identity")
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
                                .header("Origin", "https://www.youtube.com")
                                .header("Referer", "https://www.youtube.com/")
                                .build()
                            okHttpClient.newCall(request).execute().use { response ->
                                if (response.code == 403 || response.code == 429) {
                                    blockCount.incrementAndGet()
                                    stepDown()
                                    throw IOException("HTTP " + response.code + " throttled media request")
                                }
                                if (response.code != 206) throw RangeUnsupportedException("Range unsupported: HTTP " + response.code)
                                val range = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$")
                                    .matchEntire(response.header("Content-Range").orEmpty())
                                    ?: throw IOException("Invalid Content-Range")
                                val first = range.groupValues[1].toLong()
                                val last = range.groupValues[2].toLong()
                                val total = range.groupValues[3].toLong()
                                if (first != offset || last < first || last > task.end || total != expectedSize) {
                                    throw IOException("Unexpected media byte range")
                                }
                                val input = response.body?.byteStream() ?: throw IOException("Empty range body")
                                input.use {
                                    val buffer = ByteArray(DISK_BUFFER_BYTES)
                                    var position = offset
                                    while (true) {
                                        if (pauseRequestedDownloads.contains(downloadId) || !realDownloadSpecs.containsKey(downloadId)) {
                                            throw CancellationException("Download paused or cancelled")
                                        }
                                        val count = it.read(buffer)
                                        if (count < 0) break
                                        if (count == 0) continue
                                        val bytes = ByteBuffer.wrap(buffer, 0, count)
                                        while (bytes.hasRemaining()) {
                                            val written = channel.write(bytes, position)
                                            if (written <= 0) throw IOException("FileChannel positional write stalled")
                                            position += written.toLong()
                                            downloaded.addAndGet(written.toLong())
                                        }
                                        offset = position
                                    }
                                    if (position != last + 1L) throw IOException("Range response ended early")
                                }
                            }
                        }
                        consecutiveFailures.set(0)
                    } catch (error: IOException) {
                        retryCount.incrementAndGet()
                        attempts++
                        if (consecutiveFailures.incrementAndGet() >= 2) stepDown()
                        if (attempts >= MAX_RETRIES) throw error
                        Thread.sleep(when (attempts) { 1 -> 1000L; 2 -> 2000L; else -> 4000L })
                    }
                }
                completed.add((if (task.audio) "a:" else "v:") + task.index)
                saveCheckpoint()
            }
            val executor = Executors.newFixedThreadPool(DEFAULT_MAX_CONNECTIONS)
            try {
                val futures = (0 until DEFAULT_MAX_CONNECTIONS).map { worker ->
                    executor.submit(Callable {
                        while (worker < activeLimit.get()) {
                            if (pauseRequestedDownloads.contains(downloadId) || !realDownloadSpecs.containsKey(downloadId)) {
                                throw CancellationException("Download paused or cancelled")
                            }
                            val preferAudio = worker < 2 && audioUrl != null
                            val task = poll(preferAudio) ?: poll(!preferAudio) ?: break
                            runTask(task)
                        }
                    })
                }
                futures.forEach { future ->
                    try { future.get() } catch (error: ExecutionException) {
                        futures.forEach { it.cancel(true) }
                        throw (error.cause ?: error)
                    }
                }
                executor.shutdown()
                check(executor.awaitTermination(30L, TimeUnit.SECONDS)) { "Shared media workers did not terminate" }
                videoChannel.force(false)
                audioChannel?.force(false)
                val videoChunks = (videoSize + CHUNK_SIZE_BYTES - 1L) / CHUNK_SIZE_BYTES
                val audioChunks = if (audioUrl != null) (audioSize + CHUNK_SIZE_BYTES - 1L) / CHUNK_SIZE_BYTES else 0L
                check(completed.size.toLong() == videoChunks + audioChunks) { "Not all media chunks were checkpointed" }
                checkpoint.delete()
                Log.d("DL_DEBUG", "shared queue complete conn=" + activeLimit.get() +
                    " chunk=" + (CHUNK_SIZE_BYTES / (1024 * 1024)) + "MB retries=" + retryCount.get() +
                    " blocks=" + blockCount.get())
            } finally {
                executor.shutdownNow()
                runCatching { executor.awaitTermination(5L, TimeUnit.SECONDS) }
            }
        }

        private fun downloadGoogleVideoToPart(
            downloadId: String,
            videoId: String,
            itag: Int,
            streamUrl: String,
            target: File,
            streamTotalBytes: Long,
            channel: FileChannel,
            workerCount: Int,
            totalDownloadedBytes: AtomicLong
        ) {
            val host = Uri.parse(streamUrl).host?.lowercase(Locale.ROOT).orEmpty()
            check(host == "googlevideo.com" || host.endsWith(".googlevideo.com")) {
                "Unexpected media stream host"
            }
            check(streamTotalBytes > 0L) { "Stream Content-Length must be known before workers start" }
            check(workerCount in 1..6) { "Invalid stream worker count" }
            target.parentFile?.mkdirs()

            val cacheKey = videoId + ":" + itag
            val now = System.currentTimeMillis()
            val cached = prefetchedStreamChunks.get(cacheKey)
            val validCache = cached?.takeIf {
                now - it.cachedAtMs in 0..prefetchedStreamChunkTtlMs &&
                    it.bytes.isNotEmpty() &&
                    it.totalBytes == streamTotalBytes &&
                    it.bytes.size.toLong() <= streamTotalBytes
            }
            if (cached != null && validCache == null) prefetchedStreamChunks.remove(cacheKey)
            val cachedBytes = validCache?.bytes ?: ByteArray(0)
            if (cachedBytes.isNotEmpty()) {
                val cachedBuffer = ByteBuffer.wrap(cachedBytes)
                var filePosition = 0L
                while (cachedBuffer.hasRemaining()) {
                    val written = channel.write(cachedBuffer, filePosition)
                    if (written <= 0) throw IOException("Could not write prefetched stream bytes")
                    filePosition += written.toLong()
                    totalDownloadedBytes.addAndGet(written.toLong())
                }
            }

            val tasks = ArrayList<Callable<Unit>>()
            var chunkStart = 0L
            while (chunkStart < streamTotalBytes) {
                val chunkEnd = minOf(streamTotalBytes - 1L, chunkStart + downloadRangeChunkBytes - 1L)
                val firstMissingByte = if (chunkStart == 0L) cachedBytes.size.toLong() else chunkStart
                if (firstMissingByte <= chunkEnd) {
                    tasks.add(Callable {
                        downloadGoogleVideoRangeChunk(
                            downloadId, streamUrl, firstMissingByte, chunkEnd, streamTotalBytes,
                            channel, totalDownloadedBytes
                        )
                    })
                }
                chunkStart = chunkEnd + 1L
            }

            val executor = Executors.newFixedThreadPool(workerCount)
            val futures = tasks.map { executor.submit(it) }
            try {
                futures.forEach { future ->
                    try {
                        future.get()
                    } catch (error: ExecutionException) {
                        futures.forEach { it.cancel(true) }
                        throw (error.cause ?: error)
                    }
                }
                executor.shutdown()
                check(executor.awaitTermination(30L, TimeUnit.SECONDS)) {
                    "Parallel stream workers did not terminate"
                }
                channel.force(false)
            } finally {
                executor.shutdownNow()
                runCatching { executor.awaitTermination(5L, TimeUnit.SECONDS) }
            }
        }

        private fun downloadGoogleVideoRangeChunk(
            downloadId: String,
            streamUrl: String,
            initialStart: Long,
            chunkEnd: Long,
            streamTotalBytes: Long,
            channel: FileChannel,
            totalDownloadedBytes: AtomicLong
        ) {
            var nextByte = initialStart
            var retryCount = 0
            while (nextByte <= chunkEnd) {
                if (pauseRequestedDownloads.contains(downloadId) || !realDownloadSpecs.containsKey(downloadId)) {
                    throw CancellationException("Download paused or cancelled")
                }
                val request = Request.Builder()
                    .url(streamUrl)
                    .get()
                    .header("Range", "bytes=" + nextByte + "-" + chunkEnd)
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/")
                    .build()
                try {
                    JetOkHttpDownloader.withRangeRequestPermit {
                        okHttpClient.newCall(request).execute().use { response ->
                            if (response.code == 408 || response.code == 425 || response.code == 429 ||
                                response.code in 500..599
                            ) throw IOException("Transient Googlevideo HTTP " + response.code)
                            check(response.code == 206) {
                                "Googlevideo range request returned HTTP " + response.code
                            }
                            val contentRange = response.header("Content-Range")
                                ?: throw IllegalStateException("Missing Content-Range")
                            val match = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(contentRange)
                                ?: throw IllegalStateException("Invalid Content-Range: " + contentRange)
                            val actualStart = match.groupValues[1].toLong()
                            val actualEnd = match.groupValues[2].toLong()
                            val responseTotal = match.groupValues[3].toLong()
                            check(actualStart == nextByte && actualEnd >= actualStart &&
                                actualEnd <= chunkEnd && responseTotal == streamTotalBytes
                            ) { "Unexpected Googlevideo byte range" }
                            val expectedSegmentBytes = actualEnd - actualStart + 1L
                            val body = response.body ?: throw IOException("Empty Googlevideo response")
                            if (body.contentLength() >= 0L) {
                                check(body.contentLength() == expectedSegmentBytes) {
                                    "Googlevideo response body length does not match Content-Range"
                                }
                            }
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                var filePosition = nextByte
                                while (true) {
                                    if (pauseRequestedDownloads.contains(downloadId) || !realDownloadSpecs.containsKey(downloadId)) {
                                        throw CancellationException("Download paused or cancelled")
                                    }
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    check(filePosition + count.toLong() <= actualEnd + 1L) {
                                        "Googlevideo returned bytes beyond the requested range"
                                    }
                                    val byteBuffer = ByteBuffer.wrap(buffer, 0, count)
                                    while (byteBuffer.hasRemaining()) {
                                        val written = channel.write(byteBuffer, filePosition)
                                        if (written <= 0) throw IOException("FileChannel positional write made no progress")
                                        filePosition += written.toLong()
                                        totalDownloadedBytes.addAndGet(written.toLong())
                                    }
                                    nextByte = filePosition
                                }
                                if (filePosition != actualEnd + 1L) {
                                    throw IOException("Googlevideo range stream ended early at byte " + filePosition)
                                }
                            }
                        }
                    }
                    retryCount = 0
                } catch (error: IOException) {
                    if (retryCount >= 4) throw error
                    val backoffMs = 250L shl retryCount
                    retryCount++
                    try {
                        Thread.sleep(backoffMs)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw CancellationException("Download worker interrupted during retry backoff")
                    }
                }
            }
        }

        private fun containsVideoTrack(file: File): Boolean {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(file.absolutePath)
                (0 until extractor.trackCount).any { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
            } finally {
                extractor.release()
            }
        }

        private suspend fun finishDownloadSuccess(
            downloadId: String,
            url: String,
            file: File,
            mediaType: String,
            thumbnailUrl: String,
            selectedResolution: String
        ) {
            MediaScannerConnection.scanFile(applicationContext, arrayOf(file.absolutePath), null, null)
            val displaySizeBytes = verifiedNewPipeSourceSizes.remove(downloadId)?.takeIf { it > 0L }
                ?: file.length()
            val sizeStr = formatBytes(displaySizeBytes)
            val resolution = if (mediaType == "video") selectedResolution.ifBlank { extractVideoResolution(file) } else ""

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
                    thumbnailUrl = thumbnailUrl,
                    resolution = resolution
                )
            )

            val payload = DownloadCompletePayload(
                id = downloadId,
                title = file.nameWithoutExtension,
                size = sizeStr,
                filePath = file.absolutePath,
                fileUri = Uri.fromFile(file).toString(),
                thumbnailUrl = thumbnailUrl,
                resolution = resolution,
                format = file.extension.uppercase(Locale.US)
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
            activeNewPipeProcesses.remove(downloadId)?.let { runCatching { it.destroy() } }
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { YoutubeDL.getInstance().destroyProcessById("jet_dl_$downloadId") }
            }
        }

        @JavascriptInterface
        fun resumeRealDownload(downloadId: String) {
            val spec = realDownloadSpecs[downloadId] ?: return
            if (!pausedDownloads.remove(downloadId)) return
            startRealDownload(spec.id, spec.url, spec.title, spec.isAudio, spec.audioBitrate,
                spec.audioExt, spec.maxHeight, spec.selectedItag, spec.includeSubtitles, spec.thumbnailUrl, spec.selectedResolution)
        }

        @JavascriptInterface
        fun retryRealDownload(downloadId: String) {
            val spec = realDownloadSpecs[downloadId] ?: return
            startRealDownload(spec.id, spec.url, spec.title, spec.isAudio, spec.audioBitrate,
                spec.audioExt, spec.maxHeight, spec.selectedItag, spec.includeSubtitles, spec.thumbnailUrl, spec.selectedResolution)
        }

        @JavascriptInterface
        fun cancelRealDownload(downloadId: String) {
            realDownloadSpecs.remove(downloadId)
            activeNewPipeProcesses.remove(downloadId)?.let { runCatching { it.destroyForcibly() } }
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
        fun requestFileThumbnail(filePath: String, fileUri: String) {
            val path = filePath.trim()
            val uriText = fileUri.trim()
            lifecycleScope.launch(Dispatchers.IO) {
                val thumbnail = runCatching {
                    val file = path.takeIf { it.isNotBlank() }?.let(::File)
                    if (file != null && file.isFile) createSmartMediaThumbnailDataUrl(file, false)
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uriText.isNotBlank()) {
                        val bitmap = contentResolver.loadThumbnail(Uri.parse(uriText), android.util.Size(320, 180), null)
                        ByteArrayOutputStream().use { output ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)
                            bitmap.recycle()
                            "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                        }
                    } else ""
                }.getOrDefault("")
                withContext(Dispatchers.Main) {
                    if (thumbnail.isNotBlank()) callJs("onNativeFileThumbnail", "${JSONObject.quote(path)}, ${JSONObject.quote(thumbnail)}")
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
        @JavascriptInterface fun musicPlayPause(){runOnUiThread{connectMusicController();musicController?.let{if(it.isPlaying)it.pause() else it.play()}}}
        @JavascriptInterface fun musicSeekTo(positionMs:Long){runOnUiThread{connectMusicController();musicController?.seekTo(positionMs.coerceAtLeast(0L))}}
        @JavascriptInterface fun musicNext(){runOnUiThread{connectMusicController();musicController?.let{if(it.hasNextMediaItem())it.seekToNextMediaItem()}}}
        @JavascriptInterface fun musicPrevious(){runOnUiThread{connectMusicController();musicController?.let{if(it.hasPreviousMediaItem())it.seekToPreviousMediaItem() else it.seekTo(0L)}}}
        @JavascriptInterface fun musicSetShuffle(enabled:Boolean){runOnUiThread{connectMusicController();musicController?.shuffleModeEnabled=enabled}}
        @JavascriptInterface fun musicSetRepeat(mode:String){runOnUiThread{connectMusicController();musicController?.repeatMode=when(mode.lowercase(Locale.ROOT)){"one"->Player.REPEAT_MODE_ONE;"all"->Player.REPEAT_MODE_ALL;else->Player.REPEAT_MODE_OFF}}}
        @JavascriptInterface fun musicSetVolume(volume:Double) {
            runOnUiThread {
                val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val percent = (volume.coerceIn(0.0, 1.0) * 100.0).toInt().coerceIn(0, 100)
                val target = if (percent == 0) 0 else ((percent * maximum + 50) / 100).coerceIn(1, maximum)
                if (target > 0) lastMusicVolumeIndex = target
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                syncMusicVolumeToWeb()
            }
        }
        @JavascriptInterface fun musicGetQueue():String=runBlocking(Dispatchers.Main){buildMusicQueueJson()}
        @JavascriptInterface fun musicPlayIndex(index:Int){runOnUiThread{connectMusicController();musicController?.let{if(index in 0 until it.mediaItemCount){it.seekToDefaultPosition(index);it.play()}}}}
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
                addAction("Rename", "✎") { callJs("openLibraryRenameModal", JSONObject.quote(filePath) + ", " + JSONObject.quote(file.name)) }
                addAction("Delete", "⌫") { callJs("openLibraryDeleteModal", JSONObject.quote(filePath) + ", " + JSONObject.quote(file.name)) }
                dialog.setContentView(sheet)
                dialog.show()
                dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    ?.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        @JavascriptInterface
        fun renameLibraryFile(filePath: String, requestedName: String) { this@MainActivity.beginLibraryMutation("rename", filePath, requestedName) }

        @JavascriptInterface
        fun deleteLibraryFile(filePath: String) { this@MainActivity.beginLibraryMutation("delete", filePath) }

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
                runManualEngineUpdate()
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

                        var downloadedBytes = 0L
                        var totalBytes = 0L
                        var retryCount = 0
                        val buffer = ByteArray(64 * 1024)

                        while (totalBytes == 0L || downloadedBytes < totalBytes) {
                            val requestBuilder = Request.Builder()
                                .url(apkUrl)
                                .header("User-Agent", "JET-Android-Updater")
                                .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
                            if (downloadedBytes > 0L) {
                                requestBuilder.header("Range", "bytes=$downloadedBytes-")
                            }

                            var streamInterrupted = false
                            val response = try {
                                okHttpClient.newCall(requestBuilder.build()).execute()
                            } catch (networkError: java.io.IOException) {
                                streamInterrupted = true
                                null
                            }
                            if (response == null) {
                                if (retryCount >= 3) {
                                    throw IllegalStateException(
                                        "APK connection failed after 3 resume retries",
                                        java.io.IOException("Connection interrupted")
                                    )
                                }
                                retryCount++
                                val retryPercent = if (totalBytes > 0L) {
                                    ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
                                } else 0
                                callJs(
                                    "onNativeAppUpdateProgress",
                                    "$retryPercent, ${JSONObject.quote("Connection interrupted · retrying download (retry $retryCount/3)…")}"
                                )
                                continue
                            }
                            response.use { response ->
                                if (!response.isSuccessful) {
                                    throw IllegalStateException("APK download failed (HTTP ${response.code})")
                                }
                                if (response.request.url.scheme != "https") {
                                    throw IllegalStateException("APK download redirected to an insecure URL")
                                }
                                val body = response.body
                                    ?: throw IllegalStateException("APK download response was empty")
                                var expectedSegmentBytes = 0L

                                if (response.code == 206) {
                                    val range = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$")
                                        .matchEntire(response.header("Content-Range").orEmpty())
                                        ?: throw IllegalStateException("APK server returned an invalid Content-Range")
                                    val rangeStart = range.groupValues[1].toLong()
                                    val rangeEnd = range.groupValues[2].toLong()
                                    val rangeTotal = range.groupValues[3].toLong()
                                    if (rangeStart != downloadedBytes || rangeEnd < rangeStart || rangeTotal <= rangeEnd) {
                                        throw IllegalStateException("APK resume response did not match the requested byte offset")
                                    }
                                    if (totalBytes > 0L && totalBytes != rangeTotal) {
                                        throw IllegalStateException("APK size changed while resuming the download")
                                    }
                                    totalBytes = rangeTotal
                                    expectedSegmentBytes = rangeEnd - rangeStart + 1L
                                    if (body.contentLength() >= 0L && body.contentLength() != expectedSegmentBytes) {
                                        throw IllegalStateException("APK response length did not match Content-Range")
                                    }
                                } else if (response.code == 200) {
                                    // The server ignored Range: restart from byte zero instead of appending a duplicate APK.
                                    downloadedBytes = 0L
                                    totalBytes = body.contentLength()
                                    if (totalBytes <= 0L) {
                                        throw IllegalStateException("APK server did not provide a valid Content-Length")
                                    }
                                    expectedSegmentBytes = totalBytes
                                } else {
                                    throw IllegalStateException("APK server returned unsupported HTTP ${response.code}")
                                }

                                if (totalBytes <= 0L || expectedSegmentBytes <= 0L) {
                                    throw IllegalStateException("APK server did not provide a valid total size")
                                }

                                var responseBytesRead = 0L
                                body.byteStream().use { input ->
                                    FileOutputStream(apkFile, downloadedBytes > 0L).use { output ->
                                        while (true) {
                                            val count = try {
                                                input.read(buffer)
                                            } catch (readError: java.io.IOException) {
                                                streamInterrupted = true
                                                break
                                            }
                                            if (count == -1) break
                                            output.write(buffer, 0, count)
                                            responseBytesRead += count
                                            downloadedBytes += count
                                            if (responseBytesRead > expectedSegmentBytes || downloadedBytes > totalBytes) {
                                                throw IllegalStateException("APK download exceeded the advertised response size")
                                            }

                                            val percent = ((downloadedBytes * 100L) / totalBytes)
                                                .toInt().coerceIn(0, 99)
                                            val progressText =
                                                "Downloading APK · $percent% · ${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
                                            callJs(
                                                "onNativeAppUpdateProgress",
                                                "$percent, ${JSONObject.quote(progressText)}"
                                            )
                                        }
                                        output.flush()
                                    }
                                }
                                if (responseBytesRead < expectedSegmentBytes) streamInterrupted = true
                            }

                            if (downloadedBytes == totalBytes) break
                            if (downloadedBytes > totalBytes) {
                                throw IllegalStateException("Downloaded APK size exceeded the expected total")
                            }
                            if (!streamInterrupted) continue
                            if (retryCount >= 3) {
                                val reason = if (streamInterrupted) "connection repeatedly closed" else "server ended the response early"
                                throw IllegalStateException(
                                    "APK download incomplete at ${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}; $reason. Tap Try Again to retry."
                                )
                            }
                            retryCount++
                            val retryPercent = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
                            callJs(
                                "onNativeAppUpdateProgress",
                                "$retryPercent, ${JSONObject.quote("Connection interrupted · resuming download (retry $retryCount/3)…")}"
                            )
                        }

                        if (totalBytes <= 0L || downloadedBytes != totalBytes) {
                            throw IllegalStateException("APK download did not reach the exact expected byte count")
                        }
                        if (!apkFile.isFile || apkFile.length() != totalBytes) {
                            throw IllegalStateException("Downloaded APK size verification failed")
                        }
                        val archiveInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                            ?: throw IllegalStateException("Downloaded file is not a valid, complete APK archive")
                        if (archiveInfo.packageName != packageName) {
                            throw IllegalStateException("Downloaded APK does not match this JET app variant")
                        }
                    }

                    callJs(
                        "onNativeAppUpdateProgress",
                        "100, ${JSONObject.quote("APK verified · 100% · Opening installer…")}, true"
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
