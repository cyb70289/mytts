package com.example.mytts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mytts.audio.PlaybackController
import com.example.mytts.ui.theme.HighlightYellow
import com.example.mytts.util.PreferencesManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main composable screen for MyTTS.
 * Contains: text input, play/pause, stop, voice selector, slow mode toggle, status bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    controller: PlaybackController,
    prefs: PreferencesManager
) {
    val playbackState by controller.state.collectAsState()
    val currentChunkIndex by controller.currentChunkIndex.collectAsState()
    val statusMessage by controller.statusMessage.collectAsState()
    val stoppedAtOffset by controller.stoppedAtOffset.collectAsState()
    val isProcessing by controller.isProcessing.collectAsState()
    val estimatedTotalDurationMs by controller.estimatedTotalDurationMs.collectAsState()
    val estimatedCurrentPositionMs by controller.estimatedCurrentPositionMs.collectAsState()

    val savedText by prefs.text.collectAsState()
    val savedCursor by prefs.cursorPosition.collectAsState()
    val savedVoice by prefs.voice.collectAsState()
    val savedSlowMode by prefs.slowMode.collectAsState()

    // Local UI state
    var currentText by remember(savedText) { mutableStateOf(savedText) }
    var cursorPosition by remember(savedCursor) { mutableIntStateOf(savedCursor.coerceIn(0, savedText.length)) }
    var voices by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedVoice by remember(savedVoice) { mutableStateOf(savedVoice) }
    var slowMode by remember(savedSlowMode) { mutableStateOf(savedSlowMode) }
    var voiceDropdownExpanded by remember { mutableStateOf(false) }

    val isPlaying = playbackState == PlaybackController.State.PLAYING
    val isPaused = playbackState == PlaybackController.State.PAUSED
    val isLoading = playbackState == PlaybackController.State.LOADING
    val isStopped = playbackState == PlaybackController.State.STOPPED
    val isStopping = playbackState == PlaybackController.State.STOPPING
    val safeTotalDurationMs = estimatedTotalDurationMs.coerceAtLeast(0L)
    val displayCurrentPositionMs = estimatedCurrentPositionMs.coerceAtLeast(0L)
    val progressCurrentPositionMs = displayCurrentPositionMs.coerceIn(0L, safeTotalDurationMs)
    val remainingDurationMs = (safeTotalDurationMs - displayCurrentPositionMs).coerceAtLeast(0L)
    val progress = if (safeTotalDurationMs > 0L) {
        (progressCurrentPositionMs.toFloat() / safeTotalDurationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    // Stopped-state highlight range (computed from cursor position via binary search)
    var stoppedHighlightRange by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // TextLayoutResult for mapping tap coordinates to character offsets
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Flag to ensure auto-scroll on app open only happens once
    var initialScrollDone by remember { mutableStateOf(false) }

    // Compute stopped highlight whenever cursor changes, text changes, or processing finishes
    LaunchedEffect(cursorPosition, currentText, isStopped, isProcessing) {
        if (isStopped && currentText.isNotEmpty() && !isProcessing) {
            stoppedHighlightRange = controller.getChunkRangeAtOffset(
                cursorPosition.coerceIn(0, currentText.length)
            )
        }
        if (isStopped) {
            controller.previewEstimatedPosition(cursorPosition.coerceIn(0, currentText.length))
        }
    }

    // Pre-initialize engine to get voice list
    LaunchedEffect(Unit) {
        controller.preInitialize { availableVoices ->
            voices = availableVoices
            if (selectedVoice.isEmpty() && availableVoices.isNotEmpty()) {
                selectedVoice = availableVoices.first()
                prefs.saveVoice(selectedVoice)
            }
            // Pre-process saved text after engine init
            if (currentText.isNotBlank()) {
                controller.processText(currentText)
            }
        }
    }

    // Helper: compute scroll target that centers the given char offset in the viewport.
    // Uses TextLayoutResult for pixel-accurate positioning instead of linear ratio.
    fun scrollTargetForOffset(charOffset: Int): Int? {
        val layout = textLayoutResult ?: return null
        if (charOffset < 0 || charOffset > currentText.length) return null
        val safeOffset = charOffset.coerceIn(0, currentText.length)
        val line = layout.getLineForOffset(safeOffset)
        val lineTop = layout.getLineTop(line)
        val lineBottom = layout.getLineBottom(line)
        val highlightCenter = (lineTop + lineBottom) / 2f
        val viewportHeight = scrollState.viewportSize
        return (highlightCenter - viewportHeight / 2f)
            .toInt()
            .coerceIn(0, scrollState.maxValue)
    }

    // Auto-scroll to saved cursor position on app open
    // Wait until scrollState.maxValue > 0 (content has been laid out)
    LaunchedEffect(scrollState.maxValue) {
        if (!initialScrollDone && scrollState.maxValue > 0 && cursorPosition > 0 && currentText.isNotEmpty()) {
            val target = scrollTargetForOffset(cursorPosition)
            if (target != null) {
                scrollState.scrollTo(target)
            }
            initialScrollDone = true
        }
    }

    // Auto-scroll to highlighted chunk during playback and save position
    // so that if the app is killed mid-playback, the next launch resumes
    // from the last active chunk (SharedPreferences.apply() is async/batched,
    // so one write per chunk every few seconds is negligible).
    val chunkRange = controller.getCurrentChunkRange()
    LaunchedEffect(currentChunkIndex) {
        if (chunkRange != null && !isStopped) {
            val pos = chunkRange.first
            cursorPosition = pos
            prefs.saveCursorPosition(pos)
            val target = scrollTargetForOffset(pos)
            if (target != null) {
                scrollState.animateScrollTo(target)
            }
        }
    }

    // Restore cursor position when playback stops
    LaunchedEffect(stoppedAtOffset) {
        if (stoppedAtOffset >= 0 && isStopped) {
            val pos = stoppedAtOffset.coerceIn(0, currentText.length)
            cursorPosition = pos
            prefs.saveCursorPosition(pos)
            val target = scrollTargetForOffset(pos)
            if (target != null) {
                scrollState.animateScrollTo(target)
            }
        }
    }

    // Save text whenever it changes (debounced)
    LaunchedEffect(currentText) {
        delay(500) // debounce
        prefs.saveText(currentText)
    }

    LaunchedEffect(slowMode) {
        controller.refreshTimelineEstimate(slowMode)
        if (isStopped && !isProcessing) {
            controller.previewEstimatedPosition(cursorPosition.coerceIn(0, currentText.length))
        }
    }

    LaunchedEffect(isProcessing, isStopped) {
        if (!isProcessing && isStopped) {
            controller.previewEstimatedPosition(cursorPosition.coerceIn(0, currentText.length))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top row: Paste | Speaker dropdown | Slow checkbox
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Paste button — filled (highlighted) like Play, wider
            Button(
                onClick = {
                    val clip = clipboardManager.getText()?.text
                    if (!clip.isNullOrBlank()) {
                        currentText = clip
                        cursorPosition = 0
                        prefs.saveText(clip)
                        prefs.saveCursorPosition(0)
                        controller.resetPlaybackPosition()
                        controller.processText(clip)
                        // Scroll to top on paste
                        coroutineScope.launch { scrollState.scrollTo(0) }
                    }
                },
                enabled = isStopped && !isProcessing,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.weight(2f).height(36.dp)
            ) {
                Text("Paste", style = MaterialTheme.typography.bodyMedium)
            }

            // "Speaker" label
            Text(
                text = "Speaker",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Compact voice dropdown
            Box(modifier = Modifier.weight(3f)) {
                val borderColor = if (isStopped && voices.isNotEmpty())
                    MaterialTheme.colorScheme.outline
                else
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                val textColor = if (isStopped && voices.isNotEmpty())
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                        .clickable(enabled = isStopped && voices.isNotEmpty()) {
                            voiceDropdownExpanded = true
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedVoice.ifEmpty { "..." },
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        modifier = Modifier.weight(1f)
                    )
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceDropdownExpanded)
                }

                DropdownMenu(
                    expanded = voiceDropdownExpanded,
                    onDismissRequest = { voiceDropdownExpanded = false }
                ) {
                    voices.forEach { voice ->
                        DropdownMenuItem(
                            text = { Text(voice) },
                            onClick = {
                                selectedVoice = voice
                                prefs.saveVoice(voice)
                                voiceDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // Slow mode checkbox + label
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = slowMode,
                    onCheckedChange = { checked ->
                        slowMode = checked
                        prefs.saveSlowMode(checked)
                    },
                    enabled = isStopped,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = "Slow",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = formatDurationHhMmSs(displayCurrentPositionMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
                text = formatDurationHhMmSs(remainingDurationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status bar
        statusMessage?.let { msg ->
            Text(
                text = msg,
                color = if (msg.startsWith("Error")) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Text area (scrollable, highlighted in both stopped and playing states)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    MaterialTheme.shapes.medium
                )
                .padding(12.dp)
        ) {
            if (currentText.isEmpty()) {
                // Placeholder when no text
                Text(
                    text = "Paste text to begin...",
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    style = TextStyle(
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 24.sp
                    )
                )
            } else {
                // Determine which range to highlight: use playback chunk if available,
                // otherwise keep the stopped highlight (persists through LOADING state)
                val highlightRange = chunkRange ?: stoppedHighlightRange

                val annotated = buildAnnotatedString {
                    val text = currentText
                    if (highlightRange != null) {
                        val start = highlightRange.first.coerceIn(0, text.length)
                        val end = highlightRange.second.coerceIn(0, text.length)
                        append(text.substring(0, start))
                        withStyle(SpanStyle(background = HighlightYellow)) {
                            append(text.substring(start, end))
                        }
                        append(text.substring(end))
                    } else {
                        append(text)
                    }
                }

                Text(
                    text = annotated,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .then(
                            if (isStopped) {
                                Modifier.pointerInput(Unit) {
                                    detectTapGestures(
                                        onDoubleTap = { tapOffset ->
                                            // Map tap position to character offset
                                            textLayoutResult?.let { layout ->
                                                val charOffset = layout.getOffsetForPosition(tapOffset)
                                                // Find the chunk at this position and highlight it
                                                val range = controller.getChunkRangeAtOffset(charOffset)
                                                if (range != null) {
                                                    cursorPosition = range.first
                                                    prefs.saveCursorPosition(range.first)
                                                    stoppedHighlightRange = range
                                                }
                                            }
                                        },
                                        onTap = {
                                            // Consume single taps — do nothing
                                        }
                                    )
                                }
                            } else {
                                Modifier
                            }
                        ),
                    style = TextStyle(
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 24.sp
                    ),
                    onTextLayout = { result ->
                        textLayoutResult = result
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Playback controls: << (1/6) | Play (1/3) | Stop (1/3) | >> (1/6)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // << Scroll to top
            OutlinedButton(
                onClick = {
                    coroutineScope.launch { scrollState.animateScrollTo(0) }
                },
                enabled = isStopped,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text("<<")
            }

            // Play/Pause button
            Button(
                onClick = {
                    controller.togglePlayPause(
                        text = currentText,
                        cursorPosition = cursorPosition,
                        voiceName = selectedVoice,
                        slowMode = slowMode
                    )
                },
                enabled = currentText.isNotBlank() && selectedVoice.isNotEmpty() && !isLoading && !isStopping && !isProcessing,
                modifier = Modifier.weight(2f)
            ) {
                Text(
                    text = when {
                        isLoading -> "Loading..."
                        isPlaying -> "Pause"
                        isPaused -> "Resume"
                        else -> "Play"
                    }
                )
            }

            // Stop button
            OutlinedButton(
                onClick = { controller.stop() },
                enabled = !isStopped && !isStopping,
                modifier = Modifier.weight(2f)
            ) {
                Text(if (isStopping) "Stopping..." else "Stop")
            }

            // >> Scroll to bottom
            OutlinedButton(
                onClick = {
                    coroutineScope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                },
                enabled = isStopped,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text(">>")
            }
        }
    }
}

private fun formatDurationHhMmSs(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}
