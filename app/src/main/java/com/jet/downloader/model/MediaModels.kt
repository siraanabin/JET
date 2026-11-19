package com.jet.downloader.model

import kotlinx.serialization.Serializable

@Serializable
data class MediaInfoPayload(
    val ok: Boolean,
    val title: String = "",
    val duration: String = "",
    val thumbnail: String = "",
    val uploader: String = "",
    val extractor: String = "NewPipe Extractor",
    val sizes: Map<String, String> = emptyMap(),
    val error: String? = null
)

@Serializable
data class DownloadCompletePayload(
    val id: String,
    val title: String,
    val size: String,
    val filePath: String,
    val fileUri: String
)

@Serializable
data class DownloadErrorPayload(
    val id: String,
    val error: String
)

@Serializable
data class LibraryItemPayload(
    val id: Int,
    val title: String,
    val type: String,
    val meta: String,
    val duration: String,
    val filePath: String,
    val fileUri: String
)

@Serializable
data class OpenSourceLicenseItem(
    val name: String,
    val license: String,
    val category: String,
    val description: String,
    val url: String
)
