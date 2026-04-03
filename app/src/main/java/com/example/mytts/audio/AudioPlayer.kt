package com.example.mytts.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/**
 * Audio playback consumer that reads PCM chunks from a queue and plays via AudioTrack.
 * Runs on its own thread with elevated priority.
 */
class AudioPlayer(
    private val sampleRate: Int = 24000,
    private val onChunkStarted: (chunkIndex: Int) -> Unit = {},
    private val onPlaybackFinished: () -> Unit = {},
    private val onUnderrun: () -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioPlayer"
        // Crossfade samples to avoid pops at chunk boundaries
        private const val CROSSFADE_SAMPLES = 128
        // Buffering hint tone parameters
        private const val HINT_FREQUENCY_HZ = 1200.0
        private const val HINT_DURATION_MS = 150
        private const val HINT_VOLUME = 0.05f  // 5% volume
        private const val HINT_INTERVAL_MS = 1000L  // repeat once per second
    }

    data class AudioChunk(
        val pcm: FloatArray,
        val chunkIndex: Int
    )

    private val queue = LinkedBlockingQueue<AudioChunk>(8)
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val producerDone = AtomicBoolean(false)
    private var audioTrack: AudioTrack? = null
    private var hintTrack: AudioTrack? = null  // Separate static track for hint tones
    private var playerThread: Thread? = null
    private val stoppedExplicitly = AtomicBoolean(false)
    private var slowMode = false

    /**
     * Start the audio player thread.
     * @param slow if true, play at 0.75x speed
     */
    fun start(slow: Boolean = false) {
        if (running.get()) return
        slowMode = slow

        val effectiveRate = if (slow) (sampleRate * 0.75).toInt() else sampleRate

        val bufSize = AudioTrack.getMinBufferSize(
            effectiveRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(effectiveRate) // At least 1 second buffer

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(effectiveRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(bufSize * 4) // float = 4 bytes
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // Create a separate static AudioTrack for hint tones.
        // MODE_STATIC plays immediately with minimal latency, avoiding the
        // buffer-accumulation problem of writing tiny tones to the streaming track.
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audioFmt = AudioFormat.Builder()
            .setSampleRate(effectiveRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .build()
        val hintTone = generateHintTone(effectiveRate)
        hintTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttrs)
            .setAudioFormat(audioFmt)
            .setBufferSizeInBytes(hintTone.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build().also {
                it.write(hintTone, 0, hintTone.size, AudioTrack.WRITE_BLOCKING)
            }

        running.set(true)
        paused.set(false)
        producerDone.set(false)
        stoppedExplicitly.set(false)

        playerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            playLoop()
        }, "AudioPlayer").also { it.start() }
    }

    private fun playLoop() {
        val track = audioTrack ?: return
        var naturalEnd = false
        var totalFramesWritten = 0L
        var lastHintTimeMs = 0L

        try {
            track.play()
            var previousTail: FloatArray? = null

            while (running.get()) {
                // Wait while paused (between chunks)
                if (paused.get()) {
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    continue
                }

                val chunk = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }

                if (chunk == null) {
                    // Queue empty: if producer is done, we've played everything
                    if (producerDone.get()) {
                        Log.d(TAG, "Queue drained and producer done — finishing playback")
                        naturalEnd = true
                        break
                    }
                    // Underrun: producer still working but queue starved
                    if (System.currentTimeMillis() - lastHintTimeMs >= HINT_INTERVAL_MS) {
                        Log.d(TAG, "Underrun detected — playing hint tone")
                        hintTrack?.let { ht ->
                            try { ht.stop() } catch (_: Exception) {}
                            ht.reloadStaticData()
                            ht.play()
                        }
                        lastHintTimeMs = System.currentTimeMillis()
                        onUnderrun()
                    }
                    continue
                }

                // Real chunk arrived — reset hint cooldown
                lastHintTimeMs = 0L
                onChunkStarted(chunk.chunkIndex)

                var pcm = chunk.pcm
                if (pcm.isEmpty()) continue

                // Apply crossfade with previous chunk's tail to avoid pops
                if (previousTail != null && previousTail.isNotEmpty()) {
                    val fadeLen = minOf(CROSSFADE_SAMPLES, pcm.size, previousTail.size)
                    for (i in 0 until fadeLen) {
                        val fadeIn = i.toFloat() / fadeLen
                        pcm[i] = pcm[i] * fadeIn
                    }
                }

                // Save tail for next crossfade
                if (pcm.size >= CROSSFADE_SAMPLES) {
                    previousTail = pcm.sliceArray(pcm.size - CROSSFADE_SAMPLES until pcm.size)
                    val fadeLen = CROSSFADE_SAMPLES
                    val fadeStart = pcm.size - fadeLen
                    for (i in 0 until fadeLen) {
                        val fadeOut = 1.0f - (i.toFloat() / fadeLen) * 0.3f
                        pcm[fadeStart + i] = pcm[fadeStart + i] * fadeOut
                    }
                }

                // Write PCM to AudioTrack — pause-aware: waits inside the loop
                // so remaining data is written after resume (not lost)
                var offset = 0
                while (offset < pcm.size && running.get()) {
                    if (paused.get()) {
                        try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                        continue
                    }
                    val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack write error: $written")
                        break
                    }
                    offset += written
                    totalFramesWritten += written
                }
            }

            // Only stop/drain track if we weren't explicitly stopped
            // (explicit stop() handles track cleanup itself)
            if (!stoppedExplicitly.get()) {
                try {
                    track.stop()
                } catch (_: Exception) {}

                // Wait for AudioTrack to finish playing all buffered audio.
                // After stop(), playbackHeadPosition continues advancing until
                // all written frames have been rendered to hardware.
                // Do NOT call flush() here — it discards unplayed audio!
                if (naturalEnd && totalFramesWritten > 0) {
                    val timeoutMs = 5000L
                    val startTime = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startTime < timeoutMs) {
                        val headPos = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        if (headPos >= totalFramesWritten) {
                            Log.d(TAG, "AudioTrack fully drained: head=$headPos, written=$totalFramesWritten")
                            break
                        }
                        try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    }
                    if (System.currentTimeMillis() - startTime >= timeoutMs) {
                        Log.w(TAG, "AudioTrack drain timeout after ${timeoutMs}ms")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "playLoop exiting due to exception", e)
        }

        // Only fire onPlaybackFinished for natural end (not explicit stop)
        if (naturalEnd && !stoppedExplicitly.get()) {
            onPlaybackFinished()
        }
    }

    /**
     * Generate a short, quiet hint tone to signal buffering/underrun.
     * 1200Hz sine wave, ~150ms, 5% volume, with Hann window fade-in/fade-out
     * to avoid click artifacts.
     */
    private fun generateHintTone(effectiveRate: Int): FloatArray {
        val numSamples = effectiveRate * HINT_DURATION_MS / 1000
        val tone = FloatArray(numSamples)
        val angularFreq = 2.0 * PI * HINT_FREQUENCY_HZ / effectiveRate
        for (i in 0 until numSamples) {
            // Hann window envelope: smooth fade-in and fade-out
            val envelope = 0.5f * (1.0f - kotlin.math.cos(2.0f * PI.toFloat() * i / numSamples))
            tone[i] = (HINT_VOLUME * envelope * sin(angularFreq * i)).toFloat()
        }
        return tone
    }

    /**
     * Enqueue a PCM audio chunk for playback.
     * Blocks if the queue is full (backpressure on the producer).
     */
    fun enqueue(pcm: FloatArray, chunkIndex: Int): Boolean {
        if (!running.get()) return false
        return try {
            queue.offer(AudioChunk(pcm, chunkIndex), 5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
    }

    /**
     * Signal that the producer has finished submitting all chunks.
     * The player will drain remaining queue items and then stop.
     */
    fun markProducerDone() {
        producerDone.set(true)
    }

    fun pause() {
        paused.set(true)
        audioTrack?.pause()
    }

    fun resume() {
        paused.set(false)
        audioTrack?.play()
    }

    fun stop() {
        stoppedExplicitly.set(true)
        running.set(false)
        paused.set(false)
        queue.clear()
        playerThread?.interrupt()
        playerThread?.join(2000)
        playerThread = null
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }
        val ht = hintTrack
        hintTrack = null
        if (ht != null) {
            try { ht.stop() } catch (_: Exception) {}
            try { ht.release() } catch (_: Exception) {}
        }
    }

    val isRunning: Boolean get() = running.get()
    val isPaused: Boolean get() = paused.get()
    val queueSize: Int get() = queue.size
}
