package com.shortsai.cutter.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.shortsai.cutter.model.ClipSuggestion
import com.shortsai.cutter.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.*

class AiClipAnalyzer(private val context: Context) {

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.08f)
                .build()
        )
    }

    suspend fun extractMetadata(uri: Uri): VideoMetadata = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            } ?: "LongVideo.mp4"
            VideoMetadata(uri, name, dur, w, h, rot)
        } finally {
            r.release()
        }
    }

    suspend fun analyzeForShorts(
        metadata: VideoMetadata,
        targetClipSeconds: Int,
        maxClips: Int = 5,
        onProgress: (Float, String) -> Unit
    ): List<ClipSuggestion> = withContext(Dispatchers.Default) {
        val totalSec = (metadata.durationMs / 1000L).toInt().coerceAtLeast(1)
        val clipSec = targetClipSeconds.coerceIn(10, min(58, totalSec))

        if (totalSec <= clipSec) {
            onProgress(1f, "Video fits in one Short!")
            return@withContext listOf(
                ClipSuggestion(1, "Full Short Clip", 0L, metadata.durationMs, 92,
                    "Optimal Shorts length • AI framed", detectSingleFaceX(metadata))
            )
        }

        onProgress(0.05f, "Scanning audio peaks & speech hooks...")
        val audio = computeAudioEnergy(metadata.uri, totalSec) { p ->
            onProgress(0.05f + p * 0.40f, "Analyzing audio (${(p * 100).toInt()}%)...")
        }

        onProgress(0.45f, "Running on-device ML Kit face & scene scan...")
        val visual = FloatArray(totalSec) { 0.4f }
        val faceX = FloatArray(totalSec) { 0.5f }
        val hasFace = BooleanArray(totalSec)
        scanVisualFrames(metadata, totalSec, visual, faceX, hasFace) { p ->
            onProgress(0.45f + p * 0.45f, "AI Face & Scene Scan (${(p * 100).toInt()}%)...")
        }

        onProgress(0.92f, "Ranking viral Shorts highlights...")
        val combined = FloatArray(totalSec) { sec ->
            (audio[sec] * 0.55f) + (if (hasFace[sec]) 0.30f else 0.08f) + (visual[sec] * 0.15f)
        }

        data class Cand(val s: Int, val e: Int, val raw: Float, val fx: Float, val face: Boolean, val loud: Boolean)
        val step = max(2, clipSec / 6)
        val cands = mutableListOf<Cand>()
        var s = 0
        while (s + clipSec <= totalSec) {
            var sum = 0f; var aSum = 0f; var fxSum = 0f; var fHits = 0
            for (t in s until s + clipSec) {
                sum += combined[t]; aSum += audio[t]
                if (hasFace[t]) { fxSum += faceX[t]; fHits++ }
            }
            val bonus = if (s > 0 && audio[s - 1] < 0.25f) 0.08f else 0f
            val avgFx = if (fHits > 0) (fxSum / fHits).coerceIn(0.15f, 0.85f) else 0.5f
            cands.add(Cand(s, s + clipSec, sum / clipSec + bonus, avgFx, fHits >= max(1, clipSec / 5), aSum / clipSec > 0.52f))
            s += step
        }

        val sorted = cands.sortedByDescending { it.raw }
        val maxRaw = sorted.firstOrNull()?.raw?.coerceAtLeast(0.01f) ?: 1f
        val picked = mutableListOf<Cand>()
        for (c in sorted) {
            if (picked.size >= maxClips) break
            val overlaps = picked.any { min(it.e, c.e) - max(it.s, c.s) > clipSec * 0.25f }
            if (!overlaps) picked.add(c)
        }

        onProgress(1f, "Found ${picked.size} Shorts highlights!")
        picked.mapIndexed { i, c ->
            val score = ((c.raw / maxRaw) * 98f).toInt().coerceIn(62, 99)
            val reason = (if (c.loud) "High speech/audio energy" else "Dynamic pacing") +
                (if (c.face) " • Speaker auto-centered" else " • Balanced wide crop")
            ClipSuggestion(i + 1, "AI Highlight #${i + 1}", c.s * 1000L, min(c.e * 1000L, metadata.durationMs), score, reason, c.fx)
        }
    }

    private fun computeAudioEnergy(uri: Uri, totalSec: Int, onProgress: (Float) -> Unit): FloatArray {
        val energy = FloatArray(totalSec) { 0.4f }
        val counts = IntArray(totalSec)
        val raw = FloatArray(totalSec)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1; var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return energy
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(fmt, null, null, 0); start()
            }
            val strideUs = if (totalSec > 180) ((totalSec / 180.0) * 1_000_000L).toLong() else 0L
            val info = MediaCodec.BufferInfo()
            var eos = false; var lastJump = 0L
            while (!eos) {
                val inIdx = codec.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    val sz = if (buf != null) extractor.readSampleData(buf, 0) else -1
                    if (sz < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true
                    } else {
                        val pts = extractor.sampleTime
                        codec.queueInputBuffer(inIdx, 0, sz, pts, 0)
                        if (strideUs > 0L && pts - lastJump > 400_000L) {
                            lastJump = pts + strideUs
                            extractor.seekTo(lastJump, MediaExtractor.SEEK_TO_NEXT_SYNC)
                        } else extractor.advance()
                        val sec = (pts / 1_000_000L).toInt().coerceIn(0, totalSec - 1)
                        if (sec % 3 == 0) onProgress(sec.toFloat() / totalSec)
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 5_000)
                if (outIdx >= 0) {
                    val out = codec.getOutputBuffer(outIdx)
                    if (out != null && info.size > 0) {
                        out.position(info.offset); out.limit(info.offset + info.size)
                        val sb = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        var sq = 0.0; val n = sb.remaining(); var i = 0
                        while (i < n) { val v = sb.get(i) / 32768.0; sq += v * v; i += 4 }
                        val sec = (info.presentationTimeUs / 1_000_000L).toInt().coerceIn(0, totalSec - 1)
                        raw[sec] += sqrt(sq / (n / 4.0 + 1.0)).toFloat(); counts[sec]++
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
            var mx = 0.001f
            for (i in 0 until totalSec) {
                energy[i] = if (counts[i] > 0) raw[i] / counts[i] else if (i > 0) energy[i - 1] else 0.4f
                if (energy[i] > mx) mx = energy[i]
            }
            for (i in 0 until totalSec) energy[i] = (energy[i] / mx).coerceIn(0f, 1f)
        } catch (_: Exception) {
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { extractor.release() }
        }
        return energy
    }

    private suspend fun scanVisualFrames(
        meta: VideoMetadata, totalSec: Int, visual: FloatArray, faceX: FloatArray, hasFace: BooleanArray,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, meta.uri)
            val samples = min(75, totalSec)
            val step = max(1, totalSec / samples)
            var prevLuma = -1f; var lastFx = 0.5f; var lastFace = false; var lastVis = 0.4f; var idx = 0
            for (sec in 0 until totalSec step step) {
                val bmp = r.getScaledFrameAtTime(sec * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360)
                if (bmp != null) {
                    val luma = avgLuma(bmp)
                    lastVis = (if (prevLuma >= 0f) abs(luma - prevLuma) / 128f else 0.35f).coerceIn(0.1f, 1f)
                    prevLuma = luma
                    val faces = runCatching { faceDetector.process(InputImage.fromBitmap(bmp, 0)).await() }.getOrDefault(emptyList())
                    val main = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    if (main != null && bmp.width > 0) {
                        lastFx = (main.boundingBox.exactCenterX() / bmp.width.toFloat()).coerceIn(0.15f, 0.85f)
                        lastFace = true
                    } else lastFace = false
                    bmp.recycle()
                }
                for (k in sec until min(totalSec, sec + step)) {
                    visual[k] = lastVis; faceX[k] = lastFx; hasFace[k] = lastFace
                }
                onProgress((++idx / samples.toFloat()).coerceIn(0f, 1f))
            }
        } catch (_: Exception) {
        } finally { r.release() }
    }

    private suspend fun detectSingleFaceX(meta: VideoMetadata): Float = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, meta.uri)
            val bmp = r.getScaledFrameAtTime((meta.durationMs / 2L) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360) ?: return@withContext 0.5f
            val faces = runCatching { faceDetector.process(InputImage.fromBitmap(bmp, 0)).await() }.getOrDefault(emptyList())
            val fx = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?.let { (it.boundingBox.exactCenterX() / bmp.width.toFloat()).coerceIn(0.15f, 0.85f) } ?: 0.5f
            bmp.recycle(); fx
        } catch (_: Exception) { 0.5f } finally { r.release() }
    }

    private fun avgLuma(bmp: Bitmap): Float {
        var sum = 0L; var c = 0
        val sx = max(1, bmp.width / 16); val sy = max(1, bmp.height / 16)
        var y = 0; while (y < bmp.height) {
            var x = 0; while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                sum += (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                c++; x += sx
            }; y += sy
        }
        return if (c > 0) sum.toFloat() / c else 128f
    }
}
