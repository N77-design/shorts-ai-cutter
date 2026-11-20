package com.shortsai.cutter.model

import android.net.Uri

data class VideoMetadata(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int
) {
    val effectiveWidth: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
    val effectiveHeight: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
}

data class ClipSuggestion(
    val id: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val score: Int,
    val reason: String,
    val faceCenterX: Float,
    val exportProgress: Int = 0,
    val isExporting: Boolean = false,
    val exportedUri: Uri? = null,
    val errorMessage: String? = null
) {
    val durationSec: Long get() = ((endMs - startMs) / 1000L).coerceAtLeast(1L)
}
