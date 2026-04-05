package com.example.mytts.audio

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import com.example.mytts.BuildConfig
import com.example.mytts.engine.KokoroEngine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Producer thread that converts text chunks to audio using the Kokoro engine.
 * Runs inference on a background thread and feeds PCM to the AudioPlayer.
 *
 * On API 31+, uses PerformanceHintManager to tell the system about the
 * workload pattern so it can make better CPU frequency/core decisions,
 * especially important when the screen is off.
 */
class AudioProducer(
    private val context: Context,
    private val engine: KokoroEngine,
    private val player: AudioPlayer,
    private val onChunkReady: (chunkIndex: Int) -> Unit = {},
    private val onError: (chunkIndex: Int, error: Throwable) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "AudioProducer"
        // Silence threshold: samples below this absolute value are considered silent
        private const val SILENCE_THRESHOLD = 0.01f
        // Target silence gap between chunks in milliseconds
        private const val GAP_BETWEEN_CHUNKS_MS = 300
        // Target inference duration hint for PerformanceHintManager (2 seconds).
        // Set slightly below typical inference time to encourage the system to
        // boost CPU frequency rather than coast on efficiency cores.
        private const val TARGET_INFERENCE_NANOS = 2_000_000_000L
    }

    private val running = AtomicBoolean(false)
    private var producerThread: Thread? = null
    private var hintSession: Any? = null // PerformanceHintManager.Session (API 31+)

    private var chunks: List<PreparedChunk> = emptyList()
    private var voiceName: String = ""
    private var speed: Float = 1.0f
    private var startIndex: Int = 0

    /**
     * Start producing audio from the given prepared chunks.
     *
     * @param chunks list of pre-processed chunks (already normalized at paste time)
     * @param voice voice name to use
     * @param speed speed multiplier (model-level, not playback rate)
     * @param fromIndex start from this chunk index
     */
    fun start(
        chunks: List<PreparedChunk>,
        voice: String,
        speed: Float = 1.0f,
        fromIndex: Int = 0
    ) {
        stop()
        this.chunks = chunks
        this.voiceName = voice
        this.speed = speed
        this.startIndex = fromIndex

        running.set(true)
        producerThread = Thread({
            // Foreground priority so Android's EAS scheduler favors performance
            // cores and doesn't deprioritize us when the screen is off.
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            produceLoop()
        }, "AudioProducer").also { it.start() }
    }

    /**
     * Create a PerformanceHintManager session for the current thread.
     * Must be called ON the producer thread so we get the correct TID.
     * Returns the session object, or null if unavailable.
     */
    private fun createHintSession(): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            val phm = context.getSystemService(Context.PERFORMANCE_HINT_SERVICE)
                    as? PerformanceHintManager ?: return null
            val tids = intArrayOf(Process.myTid())
            val session = phm.createHintSession(tids, TARGET_INFERENCE_NANOS)
            if (BuildConfig.DEBUG) Log.d(TAG, "PerformanceHintManager session created for TID ${tids[0]}")
            session
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create PerformanceHintManager session", e)
            null
        }
    }

    /**
     * Report actual inference duration to the hint session.
     */
    private fun reportWorkDuration(durationNanos: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            (hintSession as? PerformanceHintManager.Session)
                ?.reportActualWorkDuration(durationNanos)
        } catch (_: Exception) {
            // Ignore — hint session may have been closed
        }
    }

    /**
     * Close the hint session.
     */
    private fun closeHintSession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            (hintSession as? PerformanceHintManager.Session)?.close()
        } catch (_: Exception) {}
        hintSession = null
    }

    private fun produceLoop() {
        // Create performance hint session on the producer thread
        hintSession = createHintSession()

        // Dump all chunks for debugging — verify no stale chunks from previous text
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "=== Producer starting: ${chunks.size} chunks, startIndex=$startIndex, voice=$voiceName ===")
            for (i in chunks.indices) {
                val c = chunks[i]
                Log.d(TAG, "  chunk[$i] offset=${c.startOffset}-${c.endOffset}: '${c.normalizedText.take(80)}'")
            }
        }

        try {
            for (i in startIndex until chunks.size) {
                if (!running.get()) break

                val chunk = chunks[i]
                val text = chunk.normalizedText.trim()
                if (text.isEmpty()) continue

                if (!running.get()) break

                try {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Synthesizing chunk $i: '${text.take(50)}...'")
                    val startTime = System.nanoTime()

                    val pcm = engine.speakNormalized(text, voiceName, speed)

                    val elapsedNanos = System.nanoTime() - startTime
                    reportWorkDuration(elapsedNanos)

                    val elapsedMs = elapsedNanos / 1_000_000
                    val audioDuration = pcm.size * 1000L / KokoroEngine.SAMPLE_RATE
                    if (BuildConfig.DEBUG) Log.d(TAG, "Chunk $i: ${elapsedMs}ms inference, ${audioDuration}ms audio (RTF: %.2f)".format(elapsedMs.toFloat() / audioDuration))

                    if (pcm.isNotEmpty() && running.get()) {
                        // Trim trailing silence and add a controlled gap
                        // (skip gap if next chunk continues the same sentence)
                        val nextIsContinuation = (i + 1 < chunks.size) && chunks[i + 1].isContinuation
                        val trimmed = trimAndPadSilence(pcm, KokoroEngine.SAMPLE_RATE, addGap = !nextIsContinuation)
                        player.enqueue(trimmed, i)
                        onChunkReady(i)
                    }
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.e(TAG, "Failed to synthesize chunk $i", e)
                        onError(i, e)
                    }
                }
            }
        } catch (_: InterruptedException) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Producer interrupted")
            closeHintSession()
            return
        }

        closeHintSession()

        // Signal end: let player drain its queue naturally then stop
        if (running.get()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Producer finished all chunks")
            player.markProducerDone()
        }
    }

    fun stop() {
        running.set(false)
        producerThread?.interrupt()
        // Short timeout: ONNX inference can't be interrupted mid-call, but
        // the next start() calls stop() anyway so orphaned threads self-exit.
        producerThread?.join(500)
        producerThread = null
        closeHintSession()
    }

    /**
     * Trim trailing silence from PCM audio and optionally append a silence gap.
     * When [addGap] is true (default), appends ~300ms silence for natural
     * inter-sentence pauses. When false (continuation chunks of the same
     * sentence), only trims without adding extra silence.
     *
     * @param pcm raw PCM float samples
     * @param sampleRate audio sample rate (e.g. 24000)
     * @param addGap whether to append the inter-chunk silence gap
     * @return trimmed PCM with optional trailing silence
     */
    private fun trimAndPadSilence(pcm: FloatArray, sampleRate: Int, addGap: Boolean = true): FloatArray {
        if (pcm.isEmpty()) return pcm

        // Find last non-silent sample (scanning backwards)
        var lastNonSilent = pcm.size - 1
        while (lastNonSilent >= 0 && kotlin.math.abs(pcm[lastNonSilent]) < SILENCE_THRESHOLD) {
            lastNonSilent--
        }

        // Keep audio up to last non-silent sample (with small margin to avoid cutting off decay)
        val margin = minOf(64, pcm.size - lastNonSilent - 1)
        val trimEnd = (lastNonSilent + margin + 1).coerceIn(1, pcm.size)

        // Calculate desired gap in samples (0 for continuation chunks)
        val gapSamples = if (addGap) sampleRate * GAP_BETWEEN_CHUNKS_MS / 1000 else 0

        // Create new array: trimmed audio + silence gap
        val result = FloatArray(trimEnd + gapSamples)
        System.arraycopy(pcm, 0, result, 0, trimEnd)
        // Remaining samples are already 0.0f (silence gap)

        return result
    }
}
