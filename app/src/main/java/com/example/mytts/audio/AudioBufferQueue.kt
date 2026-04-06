package com.example.mytts.audio

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A thread-safe audio buffer queue whose capacity is measured in total
 * buffered audio **duration** (sample count) rather than chunk count.
 *
 * This gives consistent buffering regardless of sentence length:
 * many short sentences or few long ones all occupy the same amount
 * of audio runway. The producer blocks on [put] when full and wakes
 * instantly when space opens — no sleep-polling.
 *
 * @param sampleRate audio sample rate (e.g. 24000)
 */
class AudioBufferQueue(private val sampleRate: Int) {

    companion object {
        /**
         * Maximum buffered audio in seconds. Adjust this value to trade
         * memory (~96 KB per second at 24 kHz mono float) against underrun
         * protection. 15 s ≈ 1.44 MB — enough to survive a ~10 s inference
         * stall when the screen is off and the CPU is throttled.
         */
        const val MAX_BUFFER_SECONDS = 10f
    }

    /** A single entry: PCM audio + the index of the originating PreparedChunk. */
    data class Entry(val pcm: FloatArray, val chunkIndex: Int)

    private val lock = ReentrantLock()
    private val notFull = lock.newCondition()
    private val notEmpty = lock.newCondition()

    private val deque = ArrayDeque<Entry>()
    private var totalSamples = 0L
    private val maxSamples = (MAX_BUFFER_SECONDS * sampleRate).toLong()

    @Volatile
    var isClosed = false
        private set

    /** Total buffered audio duration in seconds (thread-safe read). */
    val bufferedSeconds: Float
        get() = lock.withLock { totalSamples.toFloat() / sampleRate }

    /** Number of chunks currently in the buffer. */
    val size: Int
        get() = lock.withLock { deque.size }

    /**
     * Add a chunk. Blocks only when the buffer has already reached [maxSamples].
     * Accepts chunks that push total above the limit as long as the buffer was
     * below the limit when called — this keeps the producer running and starting
     * the next inference sooner, reducing underrun risk.
     *
     * @return true if the chunk was added, false if the queue was closed.
     */
    fun put(pcm: FloatArray, chunkIndex: Int): Boolean {
        lock.withLock {
            while (totalSamples >= maxSamples && !isClosed) {
                notFull.await()
            }
            if (isClosed) return false

            deque.addLast(Entry(pcm, chunkIndex))
            totalSamples += pcm.size
            notEmpty.signal()
            return true
        }
    }

    /**
     * Take the next chunk. Blocks up to [timeoutMs] if empty.
     *
     * @return the next [Entry], or null on timeout / closed-and-drained.
     */
    fun take(timeoutMs: Long): Entry? {
        lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (deque.isEmpty() && !isClosed && remaining > 0) {
                remaining = notEmpty.awaitNanos(remaining)
            }
            if (deque.isEmpty()) return null  // timeout or closed-and-drained

            val entry = deque.removeFirst()
            totalSamples -= entry.pcm.size
            notFull.signal()
            return entry
        }
    }

    /**
     * Signal that the producer is done. Remaining entries can still be
     * drained via [take]. Wakes all waiting threads.
     */
    fun close() {
        lock.withLock {
            isClosed = true
            notFull.signalAll()
            notEmpty.signalAll()
        }
    }

    /**
     * Discard all buffered entries and wake blocked threads.
     * Call from [stop] to unblock the producer immediately.
     */
    fun clear() {
        lock.withLock {
            deque.clear()
            totalSamples = 0
            isClosed = true
            notFull.signalAll()
            notEmpty.signalAll()
        }
    }
}
