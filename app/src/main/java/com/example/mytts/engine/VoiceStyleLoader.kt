package com.example.mytts.engine

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads Kokoro voice style vectors from assets/voices/.
 *
 * Kokoro v1.0 ONNX voices are stored as .bin files containing raw little-endian
 * float32 data (no header). Legacy .npy files (NumPy format) are also supported
 * as a fallback.
 *
 * Each voice is a float array of 130,560 floats (510 × 256) representing the
 * speaker's style embedding. For ONNX input, we return shape [1][N] where
 * N = total floats.
 */
class VoiceStyleLoader(private val context: Context) {

    companion object {
        private const val TAG = "VoiceStyleLoader"
        private const val VOICES_DIR = "voices"
        // Kokoro v1.0 style vector dimensions
        private const val STYLE_DIM = 256
    }

    /** Cached voice names (without extension). */
    private var voiceNames: List<String>? = null

    /** Cache loaded style vectors. */
    private val styleCache = mutableMapOf<String, Array<FloatArray>>()

    /**
     * Get list of available voice names from assets.
     * Supports both .bin (v1.0) and .npy (legacy) files.
     */
    fun getAvailableVoices(): List<String> {
        voiceNames?.let { return it }
        val names = try {
            context.assets.list(VOICES_DIR)
                ?.filter { it.endsWith(".bin") || it.endsWith(".npy") }
                ?.map { it.removeSuffix(".bin").removeSuffix(".npy") }
                ?.distinct()
                ?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list voices", e)
            emptyList()
        }
        voiceNames = names
        return names
    }

    /**
     * Load a voice style vector by name.
     * Tries .bin first (Kokoro v1.0 raw float32), then .npy (legacy NumPy format).
     * Returns a 2D array [1][N] suitable for ONNX tensor input.
     */
    fun loadVoice(name: String): Array<FloatArray> {
        styleCache[name]?.let { return it }

        val floats = tryLoadBin(name) ?: tryLoadNpy(name)
            ?: throw IllegalArgumentException("Voice '$name' not found in assets/$VOICES_DIR/")

        // Reshape flat float array to 2D [rows][STYLE_DIM]
        // Kokoro v1.0 voices: 130560 floats = 510 rows × 256 dim
        val rows = floats.size / STYLE_DIM
        require(floats.size % STYLE_DIM == 0) {
            "Voice '$name' has ${floats.size} floats, not divisible by $STYLE_DIM"
        }
        Log.i(TAG, "Loaded voice '$name': ${floats.size} floats -> [$rows][$STYLE_DIM]")

        val style = Array(rows) { row ->
            floats.copyOfRange(row * STYLE_DIM, (row + 1) * STYLE_DIM)
        }
        styleCache[name] = style
        return style
    }

    /**
     * Try to load a .bin file (raw little-endian float32 data, no header).
     */
    private fun tryLoadBin(name: String): FloatArray? {
        val path = "$VOICES_DIR/$name.bin"
        return try {
            val bytes = context.assets.open(path).use { it.readBytes() }
            if (bytes.size < 4 || bytes.size % 4 != 0) {
                Log.w(TAG, "Invalid .bin file for '$name': ${bytes.size} bytes (not aligned to float32)")
                return null
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val numFloats = bytes.size / 4
            val floats = FloatArray(numFloats)
            buf.asFloatBuffer().get(floats)
            floats
        } catch (e: java.io.FileNotFoundException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load .bin for '$name'", e)
            null
        }
    }

    /**
     * Try to load a .npy file (NumPy format v1.0/v2.0 with float32 data).
     */
    private fun tryLoadNpy(name: String): FloatArray? {
        val path = "$VOICES_DIR/$name.npy"
        return try {
            val bytes = context.assets.open(path).use { it.readBytes() }
            parseNpy(bytes)
        } catch (e: java.io.FileNotFoundException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load .npy for '$name'", e)
            null
        }
    }

    /**
     * Parse a .npy file containing a 1D float32 array.
     * Handles NumPy format v1.0 and v2.0.
     */
    private fun parseNpy(data: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Magic: \x93NUMPY
        val magic = ByteArray(6)
        buf.get(magic)
        require(magic[0] == 0x93.toByte() && String(magic, 1, 5) == "NUMPY") {
            "Not a valid .npy file"
        }

        val major = buf.get().toInt()
        @Suppress("UNUSED_VARIABLE")
        val minor = buf.get().toInt()

        val headerLen = if (major == 1) {
            buf.short.toInt() and 0xFFFF
        } else {
            buf.int
        }

        // Skip header (contains dtype, shape, order info - we assume float32 1D/2D)
        val headerBytes = ByteArray(headerLen)
        buf.get(headerBytes)

        // Remaining bytes are float32 data
        val remaining = buf.remaining()
        val numFloats = remaining / 4
        val floats = FloatArray(numFloats)
        buf.asFloatBuffer().get(floats)
        return floats
    }
}
