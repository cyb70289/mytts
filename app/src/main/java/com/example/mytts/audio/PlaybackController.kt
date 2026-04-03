package com.example.mytts.audio

import android.content.Context
import android.util.Log
import com.example.mytts.BuildConfig
import com.example.mytts.engine.KokoroEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State machine orchestrating the AudioProducer and AudioPlayer.
 * States: STOPPED -> LOADING -> PLAYING <-> PAUSED -> STOPPED
 */
class PlaybackController(
    private val context: Context
) {
    companion object {
        private const val TAG = "PlaybackController"
    }

    enum class State {
        STOPPED,
        LOADING,   // Engine initializing or first chunk being generated
        PLAYING,
        PAUSED,
        STOPPING   // Waiting for producer/player threads to finish
    }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Index of the chunk currently being played (for text highlighting). */
    private val _currentChunkIndex = MutableStateFlow(-1)
    val currentChunkIndex: StateFlow<Int> = _currentChunkIndex.asStateFlow()

    /** Status message for the UI (loading info, errors, etc.). */
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /** Whether text is being pre-processed (chunking + normalization). */
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    /**
     * Character offset where playback was stopped (or ended naturally).
     * -1 means no meaningful position. UI should watch this to restore cursor.
     */
    private val _stoppedAtOffset = MutableStateFlow(-1)
    val stoppedAtOffset: StateFlow<Int> = _stoppedAtOffset.asStateFlow()

    private var engine: KokoroEngine? = null
    private var player: AudioPlayer? = null
    private var producer: AudioProducer? = null
    private var textProcessor: TextProcessor? = null
    private var preparedChunks: List<PreparedChunk> = emptyList()
    private var processJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var initJob: Job? = null

    /**
     * Start playback from the given text at the specified cursor position.
     *
     * @param text full text to speak
     * @param cursorPosition character offset where playback should start
     * @param voiceName voice to use
     * @param slowMode if true, play at 0.75x speed
     */
    fun play(text: String, cursorPosition: Int, voiceName: String, slowMode: Boolean) {
        if (_state.value == State.PLAYING || _state.value == State.LOADING || _state.value == State.STOPPING) return

        _state.value = State.LOADING
        _statusMessage.value = "Preparing..."
        _currentChunkIndex.value = -1

        scope.launch(Dispatchers.Default) {
            try {
                // Initialize engine if needed
                if (engine == null || !engine!!.isLoaded) {
                    withContext(Dispatchers.Main) {
                        _statusMessage.value = "Loading model (may take a few seconds)..."
                    }
                    val eng = KokoroEngine(context)
                    eng.initialize()
                    engine = eng
                    // Ensure textProcessor exists
                    if (textProcessor == null) {
                        eng.phonemeConverter?.let { converter ->
                            textProcessor = TextProcessor(converter)
                        }
                    }
                }

                // If chunks weren't pre-processed (e.g. processText wasn't called),
                // do it now as a fallback
                if (preparedChunks.isEmpty() && text.isNotBlank()) {
                    textProcessor?.let { tp ->
                        preparedChunks = tp.processText(text)
                    }
                }

                // Use pre-processed chunks (already chunked + normalized at paste time)
                if (preparedChunks.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _state.value = State.STOPPED
                        _statusMessage.value = "No text to speak"
                    }
                    return@launch
                }

                // Find the starting chunk based on cursor position
                val startIndex = findChunkAtOffset(cursorPosition)

                // Create player
                val audioPlayer = AudioPlayer(
                    sampleRate = KokoroEngine.SAMPLE_RATE,
                    onChunkStarted = { index ->
                        _currentChunkIndex.value = index
                    },
                    onPlaybackFinished = {
                        scope.launch(Dispatchers.Main) {
                            if (_state.value != State.STOPPED) {
                                stop()
                            }
                        }
                    }
                )
                player = audioPlayer

                // Create producer
                val audioProducer = AudioProducer(
                    engine = engine!!,
                    player = audioPlayer,
                    onChunkReady = { index ->
                        // First chunk ready - start playback
                        if (index == startIndex && _state.value == State.LOADING) {
                            scope.launch(Dispatchers.Main) {
                                _state.value = State.PLAYING
                                _statusMessage.value = null
                            }
                        }
                    },
                    onError = { index, error ->
                        Log.e(TAG, "Synthesis error at chunk $index", error)
                        scope.launch(Dispatchers.Main) {
                            _statusMessage.value = "Error: ${error.message}"
                        }
                    }
                )
                producer = audioProducer

                // Start player first, then producer
                audioPlayer.start(slow = slowMode)
                audioProducer.start(preparedChunks, voiceName, speed = 1.0f, fromIndex = startIndex)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start playback", e)
                withContext(Dispatchers.Main) {
                    _state.value = State.STOPPED
                    _statusMessage.value = "Error: ${e.message}"
                }
            }
        }
    }

    fun pause() {
        if (_state.value != State.PLAYING) return
        player?.pause()
        _state.value = State.PAUSED
    }

    fun resume() {
        if (_state.value != State.PAUSED) return
        player?.resume()
        _state.value = State.PLAYING
    }

    fun togglePlayPause(text: String, cursorPosition: Int, voiceName: String, slowMode: Boolean) {
        when (_state.value) {
            State.STOPPED -> play(text, cursorPosition, voiceName, slowMode)
            State.PLAYING -> pause()
            State.PAUSED -> resume()
            State.LOADING -> { /* ignore during loading */ }
            State.STOPPING -> { /* ignore while stopping */ }
        }
    }

    fun stop() {
        if (_state.value == State.STOPPED || _state.value == State.STOPPING) return

        // Capture the current playback offset before clearing anything
        val offset = getCurrentPlaybackOffset()

        _state.value = State.STOPPING
        _statusMessage.value = null

        scope.launch(Dispatchers.Default) {
            try {
                // Stop player FIRST for instant silence (pause+flush is immediate),
                // then stop producer (may block briefly waiting for ONNX inference).
                player?.stop()
                player = null
                producer?.stop()
                producer = null
            } catch (e: Exception) {
                Log.e(TAG, "Error during stop cleanup", e)
            }

            withContext(Dispatchers.Main) {
                _state.value = State.STOPPED
                _currentChunkIndex.value = -1
                _statusMessage.value = null
                _stoppedAtOffset.value = offset
            }
        }
    }

    /**
     * Get the character offset of the chunk that was playing when stop was called.
     * Returns the start of the current chunk, or -1 if unknown.
     */
    private fun getCurrentPlaybackOffset(): Int {
        val index = _currentChunkIndex.value
        if (index < 0 || index >= preparedChunks.size) return -1
        return preparedChunks[index].startOffset
    }

    /**
     * Get the text range (startOffset, endOffset) of the currently playing chunk.
     * Returns null if nothing is playing.
     */
    fun getCurrentChunkRange(): Pair<Int, Int>? {
        val index = _currentChunkIndex.value
        if (index < 0 || index >= preparedChunks.size) return null
        val chunk = preparedChunks[index]
        return Pair(chunk.startOffset, chunk.endOffset)
    }

    /**
     * Get available voice names. Requires engine to be initialized.
     * If not initialized, returns empty list.
     */
    fun getAvailableVoices(): List<String> {
        return engine?.getAvailableVoices() ?: emptyList()
    }

    /**
     * Pre-initialize the engine (call early so voices list is available).
     */
    fun preInitialize(onComplete: (List<String>) -> Unit) {
        if (engine != null && engine!!.isLoaded) {
            onComplete(engine!!.getAvailableVoices())
            return
        }
        initJob = scope.launch(Dispatchers.Default) {
            try {
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Loading model..."
                }
                val eng = KokoroEngine(context)
                eng.initialize()
                engine = eng
                // Create TextProcessor now that PhonemeConverter is available
                eng.phonemeConverter?.let { converter ->
                    textProcessor = TextProcessor(converter)
                }
                val voices = eng.getAvailableVoices()
                withContext(Dispatchers.Main) {
                    _statusMessage.value = null
                    onComplete(voices)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pre-initialization failed", e)
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Model load failed: ${e.message}"
                    onComplete(emptyList())
                }
            }
        }
    }

    /**
     * Reset playback position state. Call when the text content changes
     * (e.g. paste) so stale offsets don't cause incorrect cursor placement.
     */
    fun resetPlaybackPosition() {
        _stoppedAtOffset.value = -1
        _currentChunkIndex.value = -1
        preparedChunks = emptyList()
    }

    /**
     * Pre-process text: chunk and normalize. Call on paste or app startup.
     * Runs on Dispatchers.Default. UI should observe [isProcessing] to show status.
     */
    fun processText(text: String) {
        val tp = textProcessor ?: return // Engine not initialized yet
        // Cancel any in-flight processing to prevent stale chunks from
        // a previous call overwriting our result (race condition).
        processJob?.cancel()
        if (text.isBlank()) {
            preparedChunks = emptyList()
            return
        }
        _isProcessing.value = true
        _statusMessage.value = "Processing text..."
        processJob = scope.launch(Dispatchers.Default) {
            try {
                val chunks = tp.processText(text)
                preparedChunks = chunks
                if (BuildConfig.DEBUG) Log.d(TAG, "Text processed: ${chunks.size} chunks")
            } catch (e: CancellationException) {
                throw e // Don't swallow cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Text processing failed", e)
                preparedChunks = emptyList()
            } finally {
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                    _statusMessage.value = null
                }
            }
        }
    }

    /**
     * Find which chunk contains the given character offset using binary search.
     * Chunks are sorted by startOffset (non-overlapping, contiguous).
     * Returns 0 if offset is before all chunks or chunks are empty.
     */
    private fun findChunkAtOffset(offset: Int): Int {
        val chunks = preparedChunks
        if (chunks.isEmpty()) return 0

        var lo = 0
        var hi = chunks.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (chunks[mid].endOffset <= offset) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        // lo is now the first chunk where endOffset > offset
        return if (lo < chunks.size && offset < chunks[lo].endOffset) lo else 0
    }

    /**
     * Get the text range (startOffset, endOffset) of the chunk containing the
     * given character offset. Uses binary search. Returns null if no chunks exist.
     */
    fun getChunkRangeAtOffset(offset: Int): Pair<Int, Int>? {
        val chunks = preparedChunks
        if (chunks.isEmpty()) return null

        var lo = 0
        var hi = chunks.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (chunks[mid].endOffset <= offset) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        val chunk = chunks[lo]
        return if (offset < chunk.endOffset) Pair(chunk.startOffset, chunk.endOffset) else null
    }

    /**
     * Release all resources. Call when the app is being destroyed.
     */
    fun release() {
        // Synchronous cleanup for release — bypass the async stop
        producer?.stop()
        producer = null
        player?.stop()
        player = null
        preparedChunks = emptyList()
        textProcessor = null
        _state.value = State.STOPPED
        _currentChunkIndex.value = -1
        initJob?.cancel()
        engine?.release()
        engine = null
        scope.cancel()
    }
}
