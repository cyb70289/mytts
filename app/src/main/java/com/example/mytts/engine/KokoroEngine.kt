package com.example.mytts.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log

/**
 * Wraps the Kokoro ONNX model for TTS inference.
 * Thread-safe: session.run() is synchronized internally by ORT.
 */
class KokoroEngine(private val context: Context) {
    companion object {
        private const val TAG = "KokoroEngine"
        private const val MODEL_FILE = "model_quantized.onnx"
        private const val CROSSFADE_SAMPLES = 100  // ~4ms at 24kHz
        const val SAMPLE_RATE = 24000
    }

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var _phonemeConverter: PhonemeConverter? = null
    private var voiceStyleLoader: VoiceStyleLoader? = null

    /** Expose PhonemeConverter so TextProcessor can use normalizeText(). */
    val phonemeConverter: PhonemeConverter?
        get() = _phonemeConverter

    val isLoaded: Boolean get() = session != null

    /**
     * Initialize the ONNX runtime and load the model.
     * Call from a background thread - this is slow (~2-5 seconds).
     */
    fun initialize() {
        if (session != null) return

        Log.i(TAG, "Initializing ONNX Runtime...")
        val startTime = System.currentTimeMillis()

        environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            // CPU EP is best for int8 quantized model
            // Use 1 thread for both intra-op (within an operator) and inter-op
            // (parallel graph execution) to minimize power consumption.
            // Without setInterOpNumThreads(1), ORT defaults to CPU core count,
            // spawning unnecessary threads on big.LITTLE SoCs.
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            // Graph optimization
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Flush denormal floats to zero — prevents rare but catastrophic
            // slowdowns when near-zero values hit the slow denormal path on ARM.
            addConfigEntry("session.set_denormal_as_zero", "1")
        }

        val modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
        session = environment!!.createSession(modelBytes, options)
        options.close() // SessionOptions implements AutoCloseable

        _phonemeConverter = PhonemeConverter(context)
        voiceStyleLoader = VoiceStyleLoader(context)

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "Engine initialized in ${elapsed}ms")
    }

    /**
     * Get available voice names.
     */
    fun getAvailableVoices(): List<String> {
        return voiceStyleLoader?.getAvailableVoices() ?: emptyList()
    }

    /**
     * Convert pre-normalized text to phonemes (skips normalizeText()).
     */
    fun textToPhonemeNormalized(normalizedText: String): String {
        return _phonemeConverter?.phonemizeNormalized(normalizedText)
            ?: throw IllegalStateException("Engine not initialized")
    }

    /**
     * Synthesize speech from phoneme string.
     * If the phoneme string exceeds the model's token limit, it is split at
     * a word boundary (IPA space) and each part is synthesized separately,
     * then the audio segments are concatenated with a short crossfade.
     *
     * @param phonemes IPA phoneme string
     * @param voiceName name of the voice style to use
     * @param speed playback speed multiplier (1.0 = normal, >1 = faster)
     * @return PCM float samples at [SAMPLE_RATE] Hz
     */
    fun synthesize(phonemes: String, voiceName: String, speed: Float = 1.0f): FloatArray {
        val tokens = Tokenizer.tokenizeWithPadding(phonemes)
        val maxTokens = Tokenizer.MAX_PHONEME_LENGTH + 2

        if (tokens.size <= maxTokens) {
            return synthesizeTokens(tokens, voiceName, speed)
        }

        // Phonemes exceed model limit — split at word boundary and concatenate
        Log.i(TAG, "Phonemes exceed limit (${tokens.size} tokens, max $maxTokens), splitting")
        val maxChars = Tokenizer.MAX_PHONEME_LENGTH
        val splitPos = phonemes.lastIndexOf(' ', maxChars - 1)
        if (splitPos <= 0) {
            // No word boundary — truncate as last resort
            Log.w(TAG, "No word boundary found for splitting, truncating")
            return synthesizeTokens(tokens.take(maxTokens).toLongArray(), voiceName, speed)
        }

        val firstPart = phonemes.substring(0, splitPos).trim()
        val secondPart = phonemes.substring(splitPos + 1).trim()
        if (firstPart.isEmpty()) return synthesize(secondPart, voiceName, speed)
        if (secondPart.isEmpty()) return synthesize(firstPart, voiceName, speed)

        val audio1 = synthesize(firstPart, voiceName, speed)
        val audio2 = synthesize(secondPart, voiceName, speed)

        return crossfadeConcat(audio1, audio2)
    }

    /**
     * Concatenate two audio segments with a short crossfade to avoid clicks.
     */
    private fun crossfadeConcat(a: FloatArray, b: FloatArray): FloatArray {
        // ~4ms crossfade at 24kHz — inaudible but prevents splice clicks
        val fadeLen = minOf(CROSSFADE_SAMPLES, a.size, b.size)
        val result = FloatArray(a.size + b.size - fadeLen)
        // Copy non-overlapping part of a
        a.copyInto(result, 0, 0, a.size - fadeLen)
        // Crossfade overlap region
        for (i in 0 until fadeLen) {
            val t = i.toFloat() / fadeLen
            result[a.size - fadeLen + i] = a[a.size - fadeLen + i] * (1f - t) + b[i] * t
        }
        // Copy non-overlapping part of b
        b.copyInto(result, a.size, fadeLen, b.size)
        return result
    }

    /**
     * Low-level synthesis: run ONNX inference on pre-tokenized input.
     */
    private fun synthesizeTokens(tokens: LongArray, voiceName: String, speed: Float): FloatArray {
        val sess = session ?: throw IllegalStateException("Engine not initialized")
        val loader = voiceStyleLoader ?: throw IllegalStateException("Engine not initialized")

        // Load voice style matrix [510][256] and select row by token count.
        // The style matrix is indexed by sequence length: row N = style for N tokens.
        val styleMatrix = loader.loadVoice(voiceName)
        val styleIndex = minOf(tokens.size, styleMatrix.size - 1)
        val styleRow = arrayOf(styleMatrix[styleIndex]) // shape [1][256]

        // Create ONNX tensors
        val env = environment!!
        val tokenTensor = OnnxTensor.createTensor(env, arrayOf(tokens))
        val styleTensor = OnnxTensor.createTensor(env, styleRow)
        val speedTensor = OnnxTensor.createTensor(env, floatArrayOf(speed))

        val inputs = mapOf(
            "input_ids" to tokenTensor,
            "style" to styleTensor,
            "speed" to speedTensor
        )

        return try {
            val results = sess.run(inputs)
            // Model output is 2D [1][samples] — extract the inner array
            val output = results[0].value
            val audio = if (output is FloatArray) {
                output
            } else {
                @Suppress("UNCHECKED_CAST")
                (output as Array<FloatArray>)[0]
            }
            results.close()
            audio
        } finally {
            tokenTensor.close()
            styleTensor.close()
            speedTensor.close()
        }
    }

    /**
     * Full pipeline: normalizedText -> phonemes -> synthesize.
     * Text must already be normalized (via normalizeText() at paste time).
     */
    fun speakNormalized(normalizedText: String, voiceName: String, speed: Float = 1.0f): FloatArray {
        val phonemes = textToPhonemeNormalized(normalizedText)
        return synthesize(phonemes, voiceName, speed)
    }

    fun release() {
        _phonemeConverter?.release()
        session?.close()
        session = null
        environment?.close()
        environment = null
        _phonemeConverter = null
        voiceStyleLoader = null
        Log.i(TAG, "Engine released")
    }
}
