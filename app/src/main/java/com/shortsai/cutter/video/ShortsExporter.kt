package com.shortsai.cutter.video

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.shortsai.cutter.model.ClipSuggestion
import com.shortsai.cutter.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ShortsExporter(private val context: Context) {

    @OptIn(UnstableApi::class)
    suspend fun exportShort(
        metadata: VideoMetadata,
        clip: ClipSuggestion,
        enableSmartFaceCrop: Boolean,
        enableLogColorBoost: Boolean,
        onProgress: (Int) -> Unit
    ): Uri {
        val tempDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val fileName = "ShortAI_Clip${clip.id}_${System.currentTimeMillis()}.mp4"
        val tempFile = File(tempDir, fileName)

        val videoEffects = mutableListOf<Effect>()
        val srcAspect = metadata.effectiveWidth.coerceAtLeast(1).toFloat() / metadata.effectiveHeight.coerceAtLeast(1).toFloat()
        val targetAspect = 9f / 16f

        if (srcAspect > targetAspect + 0.02f) {
            val halfW = (targetAspect / srcAspect).coerceIn(0.15f, 1f)
            val center = if (enableSmartFaceCrop) clip.faceCenterX else 0.5f
            val normCenter = ((center * 2f) - 1f).coerceIn(-1f + halfW, 1f - halfW)
            videoEffects.add(Crop((normCenter - halfW).coerceIn(-1f, 1f), (normCenter + halfW).coerceIn(-1f, 1f), -1f, 1f))
        }

        videoEffects.add(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))

        if (enableLogColorBoost) {
            videoEffects.add(Contrast(0.24f))
            videoEffects.add(HslAdjustment.Builder().adjustSaturation(22f).adjustLightness(4f).build())
        }

        val mediaItem = MediaItem.Builder()
            .setUri(metadata.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.startMs)
                    .setEndPositionMs(clip.endMs)
                    .build()
            ).build()

        val edited = EditedMediaItem.Builder(mediaItem).setEffects(Effects(emptyList(), videoEffects)).build()

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                val holder = ProgressHolder()
                var transformer: Transformer? = null

                val poll = object : Runnable {
                    override fun run() {
                        val t = transformer ?: return
                        val st = t.getProgress(holder)
                        if (st == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress.coerceIn(0, 99))
                        if (cont.isActive && st != Transformer.PROGRESS_STATE_NOT_STARTED) handler.postDelayed(this, 250)
                    }
                }

                transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(c: Composition, r: ExportResult) {
                            handler.removeCallbacks(poll); onProgress(100)
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                            handler.removeCallbacks(poll)
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }).build()

                cont.invokeOnCancellation { handler.removeCallbacks(poll); transformer?.cancel(); tempFile.delete() }
                transformer.start(edited, tempFile.absolutePath)
                handler.postDelayed(poll, 250)
            }
        }

        return saveToGallery(tempFile, fileName).also { tempFile.delete() }
    }

    private suspend fun saveToGallery(src: File, name: String): Uri = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ShortsAI")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }
}
