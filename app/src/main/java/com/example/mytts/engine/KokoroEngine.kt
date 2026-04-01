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
            // Use 1 intra-op thread for power efficiency
            setIntraOpNumThreads(1)
            // Graph optimization
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        val modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
        session = environment!!.createSession(modelBytes, options)

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
     * Convert text to phonemes (for pre-processing/chunking).
     */
    fun textToPhonemes(text: String): String {
        return _phonemeConverter?.phonemize(text)
            ?: throw IllegalStateException("Engine not initialized")
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
     *
     * If the token count exceeds the model's hard limit
     * ([Tokenizer.MAX_PHONEME_LENGTH] + 2 for BOS/EOS), splits the phoneme
     * string at the nearest word boundary and crossfade-concatenates the parts.
     *
     * Shorter-sentence truncation (the int8 duration-predictor issue) is handled
     * upstream by [TextChunker], which keeps sentences short enough that the
     * model stays in its safe samples-per-token zone.
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

        // Hard limit exceeded — split at nearest word boundary
        Log.i(TAG, "Splitting phonemes: hard token limit (${tokens.size}/$maxTokens)")
        val splitPos = phonemes.lastIndexOf(' ', Tokenizer.MAX_PHONEME_LENGTH - 1)

        if (splitPos <= 0) {
            Log.w(TAG, "No word boundary found for splitting, synthesizing as-is (clamped)")
            val clamped = tokens.take(maxTokens).toLongArray()
            return synthesizeTokens(clamped, voiceName, speed)
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
     * Full pipeline: text -> phonemes -> synthesize.
     */
    fun speak(text: String, voiceName: String, speed: Float = 1.0f): FloatArray {
        val phonemes = textToPhonemes(text)
        return synthesize(phonemes, voiceName, speed)
    }

    /**
     * Full pipeline for pre-normalized text: normalizedText -> phonemes -> synthesize.
     * Skips normalizeText() since the caller already did it at paste time.
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
