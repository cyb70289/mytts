package com.example.mytts.audio

import com.example.mytts.engine.PhonemeConverter

/**
 * A text chunk that has been pre-processed: chunked and normalized at paste time.
 * Normalization is done eagerly so that play-time only needs phonemization + synthesis.
 */
data class PreparedChunk(
    val originalText: String,     // raw text as user sees it
    val normalizedText: String,   // after normalizeText() — ready for phonemization
    val startOffset: Int,         // char position in the original full text
    val endOffset: Int
)

/**
 * Pre-processes pasted text: splits into chunks and normalizes each chunk.
 * This runs at paste time (on a background thread) so that play-time is faster.
 */
class TextProcessor(private val phonemeConverter: PhonemeConverter) {

    /**
     * Split [text] into sentence-boundary chunks and normalize each one.
     * Returns a list of [PreparedChunk] with both original and normalized text.
     */
    fun processText(text: String): List<PreparedChunk> {
        val chunks = TextChunker.chunk(text)
        return chunks.map { chunk ->
            PreparedChunk(
                originalText = chunk.text,
                normalizedText = phonemeConverter.normalizeText(chunk.text),
                startOffset = chunk.startOffset,
                endOffset = chunk.endOffset
            )
        }
    }
}
