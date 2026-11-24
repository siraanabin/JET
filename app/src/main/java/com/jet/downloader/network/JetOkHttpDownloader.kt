package com.jet.downloader.network

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

class JetOkHttpDownloader(
    private val client: OkHttpClient
) : Downloader() {

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }

    /**
     * Resolve the full stream size with the shared IPv4-preferred OkHttp client.
     * HEAD is preferred; a one-byte Range request exposes the total via Content-Range.
     */
    fun contentLength(url: String): Long {
        fun requestBuilder(): okhttp3.Request.Builder {
            val builder = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "identity")
            val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()
            if (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) {
                builder.header("Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/")
            }
            return builder
        }

        val headLength = runCatching {
            client.newCall(requestBuilder().head().build()).execute().use { response ->
                if (!response.isSuccessful) 0L
                else (response.header("Content-Length")?.toLongOrNull()
                    ?: response.body?.contentLength() ?: 0L).takeIf { it > 0L } ?: 0L
            }
        }.getOrDefault(0L)
        if (headLength > 0L) return headLength

        return runCatching {
            client.newCall(requestBuilder().get().header("Range", "bytes=0-0").build()).execute().use { response ->
                val rangeLength = response.header("Content-Range")
                    ?.substringAfterLast("/")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L } ?: 0L
                if (rangeLength > 0L) rangeLength
                else if (response.code == 200) {
                    (response.header("Content-Length")?.toLongOrNull()
                        ?: response.body?.contentLength() ?: 0L).takeIf { it > 0L } ?: 0L
                } else 0L
            }
        }.getOrDefault(0L)
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBody = dataToSend?.toRequestBody(null)
        val okRequestBuilder = okhttp3.Request.Builder()
            .method(httpMethod, requestBody)
            .url(url)
            .addHeader("User-Agent", USER_AGENT)

        for ((headerName, headerValueList) in headers) {
            if (headerValueList.size > 1) {
                okRequestBuilder.removeHeader(headerName)
                for (headerValue in headerValueList) {
                    okRequestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                okRequestBuilder.header(headerName, headerValueList[0])
            }
        }

        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()
        if (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) {
            okRequestBuilder
                .header("Accept-Encoding", "identity")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
        }

        client.newCall(okRequestBuilder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            val responseBodyToReturn = response.body?.string()
            val latestUrl = response.request.url.toString()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBodyToReturn,
                latestUrl
            )
        }
    }
}
