package com.shortsai.cutter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shortsai.cutter.model.ClipSuggestion
import com.shortsai.cutter.ui.ShortsViewModel

private val DarkPalette = darkColorScheme(
    primary = Color(0xFFFF2E55), onPrimary = Color.White,
    secondary = Color(0xFF6C63FF), onSecondary = Color.White,
    background = Color(0xFF0E0F14), onBackground = Color(0xFFF3F4F8),
    surface = Color(0xFF171923), onSurface = Color(0xFFF3F4F8),
    surfaceVariant = Color(0xFF222534), onSurfaceVariant = Color(0xFFB5BAC9)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = DarkPalette) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ShortsScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortsScreen(vm: ShortsViewModel = viewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onVideoSelected(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("ShortsAI Cutter", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("S23 Ultra • Android 17 On-Device AI", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    if (state.clips.isNotEmpty() && !state.isAnalyzing) {
                        TextButton(onClick = vm::exportAllClips, enabled = !state.isBatchExporting) {
                            Text(if (state.isBatchExporting) "Exporting..." else "Export All")
                        }
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        Button(
                            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.VideoLibrary, null)
                            Spacer(Modifier.width(10.dp))
                            Text(if (state.selectedVideo == null) "Pick Long / Log Video" else "Change Video", fontWeight = FontWeight.SemiBold)
                        }
                        state.selectedVideo?.let { v ->
                            Spacer(Modifier.height(10.dp))
                            Text("${v.displayName} • ${fmtTime(v.durationMs)} • ${v.effectiveWidth}×${v.effectiveHeight}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("Target Shorts Length", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 45, 58).forEach { sec ->
                                FilterChip(selected = state.targetDurationSec == sec, onClick = { vm.setTargetDuration(sec) }, label = { Text("${sec}s") })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        ToggleRow("AI Face-Center 9:16 Crop", "Keeps speaker centered in vertical crop", state.enableSmartFaceCrop, vm::toggleSmartFaceCrop)
                        Spacer(Modifier.height(6.dp))
                        ToggleRow("Log-to-Vivid Color Boost", "Auto-grades flat Samsung Log footage", state.enableLogColorBoost, vm::toggleLogColorBoost)
                    }
                }
            }
            if (state.isAnalyzing) {
                item {
                    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(state.analysisStatus, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { state.analysisProgress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)))
                        }
                    }
                }
            }
            state.globalError?.let { err -> item { Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
            items(state.clips, key = { it.id }) { clip ->
                ClipCard(clip, { vm.adjustClipPan(clip.id, it) }, { vm.exportClip(clip.id) }, { shareToYouTube(ctx, it) })
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ClipCard(clip: ClipSuggestion, onPan: (Float) -> Unit, onExport: () -> Unit, onShare: (Uri) -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(clip.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${fmtTime(clip.startMs)} → ${fmtTime(clip.endMs)} (${clip.durationSec}s)", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF272B3F)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("AI Score ${clip.score}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4ADE80))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(clip.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("9:16 Pan", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = clip.faceCenterX, onValueChange = onPan, valueRange = 0.1f..0.9f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                Text("${(clip.faceCenterX * 100).toInt()}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (clip.isExporting) {
                LinearProgressIndicator(progress = { clip.exportProgress / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)))
                Text("Hardware rendering 1080×1920... ${clip.exportProgress}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            clip.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onExport, enabled = !clip.isExporting, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                    Icon(if (clip.exportedUri != null) Icons.Rounded.CheckCircle else Icons.Rounded.ContentCut, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (clip.exportedUri != null) "Re-Export" else "Export 9:16")
                }
                if (clip.exportedUri != null) {
                    FilledTonalButton(onClick = { onShare(clip.exportedUri) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("YouTube Shorts")
                    }
                }
            }
        }
    }
}

private fun fmtTime(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(s / 60L, s % 60L)
}

private fun shareToYouTube(ctx: Context, uri: Uri) {
    val yt = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri)
        setPackage("com.google.android.youtube"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try { ctx.startActivity(yt) } catch (_: Exception) {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Upload Short"))
        Toast.makeText(ctx, "Saved to Movies/ShortsAI in Gallery!", Toast.LENGTH_SHORT).show()
    }
}
