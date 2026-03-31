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
     * @param phonemes IPA phoneme string (max ~400 chars)
     * @param voiceName name of the voice style to use
     * @param speed playback speed multiplier (1.0 = normal, >1 = faster)
     * @return PCM float samples at [SAMPLE_RATE] Hz
     */
    fun synthesize(phonemes: String, voiceName: String, speed: Float = 1.0f): FloatArray {
        val sess = session ?: throw IllegalStateException("Engine not initialized")
        val loader = voiceStyleLoader ?: throw IllegalStateException("Engine not initialized")

        // Tokenize with padding
        val tokens = Tokenizer.tokenizeWithPadding(phonemes)
        if (tokens.size > Tokenizer.MAX_PHONEME_LENGTH + 2) {
            Log.w(TAG, "Phonemes too long (${tokens.size} tokens), truncating")
        }
        val truncatedTokens = if (tokens.size > Tokenizer.MAX_PHONEME_LENGTH + 2) {
            tokens.take(Tokenizer.MAX_PHONEME_LENGTH + 2).toLongArray()
        } else {
            tokens
        }

        // Load voice style matrix [510][256] and select row by token count.
        // The style matrix is indexed by sequence length: row N = style for N tokens.
        val styleMatrix = loader.loadVoice(voiceName)
        val styleIndex = minOf(truncatedTokens.size, styleMatrix.size - 1)
        val styleRow = arrayOf(styleMatrix[styleIndex]) // shape [1][256]

        // Create ONNX tensors
        val env = environment!!
        val tokenTensor = OnnxTensor.createTensor(env, arrayOf(truncatedTokens))
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
        session?.close()
        session = null
        environment?.close()
        environment = null
        _phonemeConverter = null
        voiceStyleLoader = null
        Log.i(TAG, "Engine released")
    }
}
