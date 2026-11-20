package com.shortsai.cutter.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shortsai.cutter.ai.AiClipAnalyzer
import com.shortsai.cutter.model.ClipSuggestion
import com.shortsai.cutter.model.VideoMetadata
import com.shortsai.cutter.video.ShortsExporter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ShortsUiState(
    val selectedVideo: VideoMetadata? = null,
    val targetDurationSec: Int = 30,
    val enableSmartFaceCrop: Boolean = true,
    val enableLogColorBoost: Boolean = false,
    val isAnalyzing: Boolean = false,
    val analysisProgress: Float = 0f,
    val analysisStatus: String = "Pick a long or Log video to auto-generate Shorts.",
    val clips: List<ClipSuggestion> = emptyList(),
    val isBatchExporting: Boolean = false,
    val globalError: String? = null
)

class ShortsViewModel(application: Application) : AndroidViewModel(application) {
    private val analyzer = AiClipAnalyzer(application)
    private val exporter = ShortsExporter(application)
    private val _uiState = MutableStateFlow(ShortsUiState())
    val uiState: StateFlow<ShortsUiState> = _uiState.asStateFlow()

    fun onVideoSelected(uri: Uri) = viewModelScope.launch {
        _uiState.update { it.copy(isAnalyzing = true, analysisProgress = 0.02f, analysisStatus = "Reading metadata...", clips = emptyList(), globalError = null) }
        try {
            val meta = analyzer.extractMetadata(uri)
            _uiState.update { it.copy(selectedVideo = meta) }
            runAi(meta)
        } catch (e: Exception) {
            _uiState.update { it.copy(isAnalyzing = false, globalError = "Load failed: ${e.localizedMessage}") }
        }
    }

    fun setTargetDuration(sec: Int) {
        _uiState.update { it.copy(targetDurationSec = sec) }
        _uiState.value.selectedVideo?.let { viewModelScope.launch { runAi(it) } }
    }

    fun toggleSmartFaceCrop(v: Boolean) = _uiState.update { it.copy(enableSmartFaceCrop = v) }
    fun toggleLogColorBoost(v: Boolean) = _uiState.update { it.copy(enableLogColorBoost = v) }
    fun adjustClipPan(id: Int, x: Float) = updateClip(id) { it.copy(faceCenterX = x.coerceIn(0f, 1f)) }

    private suspend fun runAi(meta: VideoMetadata) {
        _uiState.update { it.copy(isAnalyzing = true, analysisProgress = 0.05f, globalError = null) }
        try {
            val found = analyzer.analyzeForShorts(meta, _uiState.value.targetDurationSec) { p, msg ->
                _uiState.update { it.copy(analysisProgress = p, analysisStatus = msg) }
            }
            _uiState.update { it.copy(isAnalyzing = false, analysisProgress = 1f, analysisStatus = "Ready! ${found.size} Shorts found.", clips = found) }
        } catch (e: Exception) {
            _uiState.update { it.copy(isAnalyzing = false, globalError = "AI error: ${e.localizedMessage}") }
        }
    }

    fun exportClip(id: Int) {
        val v = _uiState.value.selectedVideo ?: return
        val c = _uiState.value.clips.firstOrNull { it.id == id } ?: return
        if (!c.isExporting) viewModelScope.launch { exportOne(v, c) }
    }

    fun exportAllClips() {
        val s = _uiState.value; val v = s.selectedVideo ?: return
        if (s.isBatchExporting || s.clips.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBatchExporting = true) }
            for (c in _uiState.value.clips) if (c.exportedUri == null) exportOne(v, c)
            _uiState.update { it.copy(isBatchExporting = false) }
        }
    }

    private suspend fun exportOne(v: VideoMetadata, c: ClipSuggestion) {
        updateClip(c.id) { it.copy(isExporting = true, exportProgress = 0, errorMessage = null) }
        try {
            val uri = exporter.exportShort(v, c, _uiState.value.enableSmartFaceCrop, _uiState.value.enableLogColorBoost) { pct ->
                updateClip(c.id) { it.copy(exportProgress = pct) }
            }
            updateClip(c.id) { it.copy(isExporting = false, exportProgress = 100, exportedUri = uri) }
        } catch (e: Exception) {
            updateClip(c.id) { it.copy(isExporting = false, errorMessage = "Export failed: ${e.localizedMessage}") }
        }
    }

    private fun updateClip(id: Int, fn: (ClipSuggestion) -> ClipSuggestion) {
        _uiState.update { s -> s.copy(clips = s.clips.map { if (it.id == id) fn(it) else it }) }
    }
}
