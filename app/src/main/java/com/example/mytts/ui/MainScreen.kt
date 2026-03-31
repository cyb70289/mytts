package com.example.mytts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
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

    val savedText by prefs.text.collectAsState()
    val savedCursor by prefs.cursorPosition.collectAsState()
    val savedVoice by prefs.voice.collectAsState()
    val savedSlowMode by prefs.slowMode.collectAsState()

    // Local UI state
    var textFieldValue by remember(savedText) {
        mutableStateOf(
            TextFieldValue(
                text = savedText,
                selection = TextRange(savedCursor.coerceIn(0, savedText.length))
            )
        )
    }
    var voices by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedVoice by remember(savedVoice) { mutableStateOf(savedVoice) }
    var slowMode by remember(savedSlowMode) { mutableStateOf(savedSlowMode) }
    var voiceDropdownExpanded by remember { mutableStateOf(false) }

    val isPlaying = playbackState == PlaybackController.State.PLAYING
    val isPaused = playbackState == PlaybackController.State.PAUSED
    val isLoading = playbackState == PlaybackController.State.LOADING
    val isStopped = playbackState == PlaybackController.State.STOPPED
    val isStopping = playbackState == PlaybackController.State.STOPPING

    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current

    // Pre-initialize engine to get voice list
    LaunchedEffect(Unit) {
        controller.preInitialize { availableVoices ->
            voices = availableVoices
            if (selectedVoice.isEmpty() && availableVoices.isNotEmpty()) {
                selectedVoice = availableVoices.first()
                prefs.saveVoice(selectedVoice)
            }
            // Pre-process saved text after engine init
            if (textFieldValue.text.isNotBlank()) {
                controller.processText(textFieldValue.text)
            }
        }
    }

    // Auto-scroll to highlighted chunk during playback
    val chunkRange = controller.getCurrentChunkRange()
    LaunchedEffect(currentChunkIndex) {
        if (chunkRange != null && !isStopped) {
            // Estimate scroll position based on text offset
            val textLen = textFieldValue.text.length
            if (textLen > 0) {
                val ratio = chunkRange.first.toFloat() / textLen
                val target = (scrollState.maxValue * ratio).toInt()
                scrollState.animateScrollTo(target)
            }
        }
    }

    // Restore cursor position when playback stops
    LaunchedEffect(stoppedAtOffset) {
        if (stoppedAtOffset >= 0 && isStopped) {
            val pos = stoppedAtOffset.coerceIn(0, textFieldValue.text.length)
            textFieldValue = textFieldValue.copy(
                selection = TextRange(pos)
            )
            prefs.saveCursorPosition(pos)
            // Scroll so stopped position is visible (center-ish)
            val textLen = textFieldValue.text.length
            if (textLen > 0) {
                val ratio = pos.toFloat() / textLen
                val target = (scrollState.maxValue * ratio).toInt()
                scrollState.animateScrollTo(target)
            }
            // Focus the text field
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    // Save text whenever it changes (debounced)
    LaunchedEffect(textFieldValue.text) {
        delay(500) // debounce
        prefs.saveText(textFieldValue.text)
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
            // Paste button
            OutlinedButton(
                onClick = {
                    val clip = clipboardManager.getText()?.text
                    if (!clip.isNullOrBlank()) {
                        textFieldValue = TextFieldValue(
                            text = clip,
                            selection = TextRange(0)
                        )
                        prefs.saveText(clip)
                        prefs.saveCursorPosition(0)
                        controller.resetPlaybackPosition()
                        controller.processText(clip)
                    }
                },
                enabled = isStopped && !isProcessing,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("Paste", style = MaterialTheme.typography.bodyMedium)
            }

            // "Speaker" label
            Text(
                text = "Speaker",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Compact voice dropdown
            Box(modifier = Modifier.weight(1f)) {
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

        // Text area (scrollable, read-only with cursor when stopped, highlighted during playback)
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
            if (isStopped) {
                // Read-only text field when stopped — allows cursor positioning and scrolling
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        // Only allow selection/cursor changes, not text edits
                        if (newValue.text == textFieldValue.text) {
                            textFieldValue = newValue
                            prefs.saveCursorPosition(newValue.selection.start)
                        }
                    },
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .verticalScroll(scrollState),
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 24.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        if (textFieldValue.text.isEmpty()) {
                            Text(
                                text = "Paste text to begin...",
                                style = TextStyle(
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                        innerTextField()
                    }
                )
            } else {
                // Read-only text with highlighted current chunk during playback
                val annotated = buildAnnotatedString {
                    val text = textFieldValue.text
                    val range = chunkRange
                    if (range != null) {
                        val start = range.first.coerceIn(0, text.length)
                        val end = range.second.coerceIn(0, text.length)
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
                        .verticalScroll(scrollState),
                    style = TextStyle(
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 24.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Playback controls: << (1/6) | Play (1/3) | Stop (1/3) | >> (1/6)
        val coroutineScope = rememberCoroutineScope()
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
                        text = textFieldValue.text,
                        cursorPosition = textFieldValue.selection.start,
                        voiceName = selectedVoice,
                        slowMode = slowMode
                    )
                },
                enabled = textFieldValue.text.isNotBlank() && selectedVoice.isNotEmpty() && !isLoading && !isStopping && !isProcessing,
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
