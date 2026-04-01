package com.example.mytts.audio

import android.os.Process
import android.util.Log
import com.example.mytts.engine.KokoroEngine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Producer thread that converts text chunks to audio using the Kokoro engine.
 * Runs inference on a background thread and feeds PCM to the AudioPlayer.
 */
class AudioProducer(
    private val engine: KokoroEngine,
    private val player: AudioPlayer,
    private val onChunkReady: (chunkIndex: Int) -> Unit = {},
    private val onError: (chunkIndex: Int, error: Throwable) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "AudioProducer"
        // Max chunks to queue ahead of playback
        private const val MAX_QUEUE_AHEAD = 4
        // Silence threshold: samples below this absolute value are considered silent
        private const val SILENCE_THRESHOLD = 0.002f
        // Target silence gap between chunks in milliseconds
        private const val GAP_BETWEEN_CHUNKS_MS = 300
        // Safety margin after last non-silent sample to preserve decays
        private const val TRIM_MARGIN_SAMPLES = 480  // ~20ms at 24kHz
    }

    private val running = AtomicBoolean(false)
    private var producerThread: Thread? = null

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
            // Run at background priority but on performance cores
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            produceLoop()
        }, "AudioProducer").also { it.start() }
    }

    private fun produceLoop() {
        try {
            for (i in startIndex until chunks.size) {
                if (!running.get()) break

                val chunk = chunks[i]
                val text = chunk.normalizedText.trim()
                if (text.isEmpty()) continue

                // Wait if player queue is full (backpressure)
                while (running.get() && player.queueSize >= MAX_QUEUE_AHEAD) {
                    try { Thread.sleep(100) } catch (_: InterruptedException) { return }
                }
                if (!running.get()) break

                try {
                    val pcm = engine.speakNormalized(text, voiceName, speed)

                    if (pcm.isNotEmpty() && running.get()) {
                        // Trim trailing silence and add a controlled gap
                        val trimmed = trimAndPadSilence(pcm, KokoroEngine.SAMPLE_RATE)
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
            return
        }

        // Signal end: let player drain its queue naturally then stop
        if (running.get()) {
            player.markProducerDone()
        }
    }

    fun stop() {
        running.set(false)
        producerThread?.interrupt()
        producerThread?.join(3000)
        producerThread = null
    }

    val isRunning: Boolean get() = running.get()

    /**
     * Trim trailing silence from PCM audio and append a controlled silence gap.
     * This ensures consistent ~300ms gaps between chunks instead of variable
     * silence durations from the model output.
     *
     * @param pcm raw PCM float samples
     * @param sampleRate audio sample rate (e.g. 24000)
     * @return trimmed PCM with controlled trailing silence
     */
    private fun trimAndPadSilence(pcm: FloatArray, sampleRate: Int): FloatArray {
        if (pcm.isEmpty()) return pcm

        // Find last sample above the silence threshold (scanning backwards).
        // Using a lower threshold (0.002 ≈ -54dB) to avoid cutting off quiet
        // tail content that the model actually generated.
        var lastNonSilent = pcm.size - 1
        while (lastNonSilent >= 0 && kotlin.math.abs(pcm[lastNonSilent]) < SILENCE_THRESHOLD) {
            lastNonSilent--
        }

        // Keep audio up to last non-silent sample plus a safety margin (~20ms)
        // to preserve any natural decay or release that falls below threshold.
        val margin = minOf(TRIM_MARGIN_SAMPLES, pcm.size - lastNonSilent - 1)
        val trimEnd = (lastNonSilent + margin + 1).coerceIn(1, pcm.size)

        // Calculate desired gap in samples
        val gapSamples = sampleRate * GAP_BETWEEN_CHUNKS_MS / 1000

        // Create new array: trimmed audio + silence gap
        val result = FloatArray(trimEnd + gapSamples)
        System.arraycopy(pcm, 0, result, 0, trimEnd)
        // Remaining samples are already 0.0f (silence gap)

        return result
    }
}
